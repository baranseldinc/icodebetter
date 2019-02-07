package iwb.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import org.json.JSONObject;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import iwb.cache.FrameworkCache;
import iwb.cache.FrameworkSetting;
import iwb.cache.LocaleMsgCache;
import iwb.custom.trigger.GlobalFuncTrigger;
import iwb.dao.rdbms_impl.MetadataLoaderDAO;
import iwb.dao.rdbms_impl.PostgreSQL;
import iwb.domain.db.Log5GlobalFuncAction;
import iwb.domain.db.W5GlobalFuncParam;
import iwb.domain.db.W5LookUp;
import iwb.domain.db.W5LookUpDetay;
import iwb.domain.db.W5Query;
import iwb.domain.db.W5QueryField;
import iwb.domain.db.W5QueryParam;
import iwb.domain.db.W5Table;
import iwb.domain.db.W5TableEvent;
import iwb.domain.db.W5WsMethod;
import iwb.domain.db.W5WsMethodParam;
import iwb.domain.result.W5FormResult;
import iwb.domain.result.W5GlobalFuncResult;
import iwb.domain.result.W5QueryResult;
import iwb.exception.IWBException;
import iwb.script.GraalScript;
import iwb.script.NashornScript;
import iwb.script.RhinoScript;
import iwb.util.GenericUtil;
import iwb.util.RhinoContextFactory;
import iwb.util.RhinoUtil;

import org.graalvm.polyglot.Value;

import jdk.nashorn.api.scripting.ScriptObjectMirror;

@Component
public class GlobalScriptEngine {
	private static boolean useNashorn = true;

	@Lazy
	@Autowired
	private PostgreSQL dao;

	@Lazy
	@Autowired
	private MetadataLoaderDAO metaDataDao;

	@Lazy
	@Autowired
	private ConversionEngine conversionEngine;

	@Lazy
	@Autowired
	private QueryEngine queryEngine;

	@Lazy
	@Autowired
	private WorkflowEngine workflowEngine;

	@Lazy
	@Autowired
	private AccessControlEngine acEngine;

	@Lazy
	@Autowired
	private CRUDEngine crudEngine;

	@Lazy
	@Autowired
	private RESTEngine restEngine;

	public PostgreSQL getDao() {
		return dao;
	}

	public ConversionEngine getConversionEngine() {
		return conversionEngine;
	}

	public QueryEngine getQueryEngine() {
		return queryEngine;
	}

	public CRUDEngine getCrudEngine() {
		return crudEngine;
	}

	public RESTEngine getRestEngine() {
		return restEngine;
	}

	private ScriptEngine nashornEngine = null;
	private org.graalvm.polyglot.Context polyglot = null;

	private List<Object> fromScriptObject2List(ScriptObjectMirror jsRequestParams) {
		List ll = new ArrayList();
		for (Object oo : jsRequestParams.values()) {
			if (oo == null)
				ll.add(null);
			else if (oo instanceof ScriptObjectMirror)
				if (((ScriptObjectMirror) oo).isArray()) {
					ll.add(fromScriptObject2List((ScriptObjectMirror) oo));
				} else
					ll.add(fromScriptObject2Map((ScriptObjectMirror) oo));
			else
				ll.add(oo);
		}
		return ll;
	}

	private Map<String, Object> fromScriptObject2Map(ScriptObjectMirror jsRequestParams) {
		Map<String, Object> rp = new HashMap<String, Object>();
		if (jsRequestParams != null) {
			if (jsRequestParams.isArray()) {
				rp.put("rhino", fromScriptObject2List(jsRequestParams));
				return rp;

			}

			for (String key : jsRequestParams.keySet()) {
				Object o = jsRequestParams.get(key);
				if (o != null) {
					String res = o.toString();
					if (res.endsWith(".0") && GenericUtil.uInt(res.substring(0, res.length() - 2)) > 0)
						res = res.substring(0, res.length() - 2);
					rp.put(key, res);
				}
			}
		}
		return rp;
	}

	public W5GlobalFuncResult executeGlobalFunc(Map<String, Object> scd, int globalFuncId,
			Map<String, String> parameterMap, short accessSourceType) {

		W5GlobalFuncResult r = metaDataDao.getGlobalFuncResult(scd, globalFuncId);
		if (!GenericUtil.isEmpty(r.getGlobalFunc().getAccessSourceTypes())
				&& !GenericUtil.hasPartInside2(r.getGlobalFunc().getAccessSourceTypes(), accessSourceType))
			throw new IWBException("security", "GlobalFunc", globalFuncId, null, "Access Source Type Control", null);
		/*
		 * if(execRestrictTip!=4 && checkAccessRecordControlViolation(scd, 4, 20,
		 * ""+dbFuncId)) throw new PromisException("security", "DbProc Execute2",
		 * dbFuncId, null, "Access Execute Control", null);
		 */
		dao.checkTenant(scd);
		r.setErrorMap(new HashMap());
		r.setRequestParams(parameterMap);
		GlobalFuncTrigger.beforeExec(r);

		Log5GlobalFuncAction action = new Log5GlobalFuncAction(r);
		String error = null;

		boolean hasOutParam = false;
		Object requestJson = r.getRequestParams().get("_json");
		String script = r.getGlobalFunc().getRhinoScriptCode();
		if (script.length() > 0 && script.charAt(0) == '!')
			script = script.substring(1);
		List<Object> params = new ArrayList();

		switch (r.getGlobalFunc().getLkpCodeType()) {
		case 1:
		case 10:// NashornJS
			if (nashornEngine == null)
				nashornEngine = new ScriptEngineManager().getEngineByName("nashorn");
			Object nobj = FrameworkCache.getGraalFunc(scd, "20." + globalFuncId);
			String fncName = null;
			if (nobj == null)
				try {
					fncName = "fnc_" + (r.getScd() != null && r.getScd().get("projectId") != null
							? r.getScd().get("projectId").toString().replace('-', '_')
							: "xxx") + "_" + r.getGlobalFuncId();
					StringBuilder sb = new StringBuilder();
					StringBuilder sbPost1 = new StringBuilder(), sbPost2 = new StringBuilder();
					sb.append("function ").append(fncName).append("($, _scd, _request");
					if (!GenericUtil.isEmpty(r.getGlobalFunc().get_dbFuncParamList())) {
						for (W5GlobalFuncParam p1 : r.getGlobalFunc().get_dbFuncParamList())
							if (p1.getOutFlag() == 0) {
								sb.append(",").append(p1.getDsc());
							} else {
								hasOutParam = true;
								sbPost1.append(p1.getDsc()).append("=null, ");
								sbPost2.append(p1.getDsc()).append(":").append(p1.getDsc()).append(", ");
							}
					}
					if (requestJson != null && requestJson instanceof JSONObject) {
						sb.append(",json");
						r.getRequestParams().remove("_json");
					} else
						requestJson = null;

					sb.append("){\n");
					if (hasOutParam) {
						sbPost1.setLength(sbPost1.length() - 2);
						sbPost2.setLength(sbPost2.length() - 2);
						sb.append("var ").append(sbPost1).append(";\n").append(script).append("\nreturn {")
								.append(sbPost2).append("}");

					} else
						sb.append(script);
					sb.append("\n}");
					script = sb.toString();
					nashornEngine.eval(script);
					FrameworkCache.addGraalFunc(scd, "20." + globalFuncId, fncName);
				} catch (Exception ge) {
					dao.logGlobalFuncAction(action, r, error);
					throw new IWBException("rhino", "NashornGlobalFunc.Compile", r.getGlobalFuncId(), script,
							"[20," + r.getGlobalFuncId() + "] " + r.getGlobalFunc().getDsc(), ge);
				}
			else if (nobj instanceof String) {
				fncName = nobj.toString();
			}
			params.add(new NashornScript(r.getScd(), r.getRequestParams(), this));
			params.add(scd);
			params.add(parameterMap);

			if (requestJson != null && requestJson instanceof JSONObject) {
				params.add(requestJson);
			} else if (!GenericUtil.isEmpty(r.getGlobalFunc().get_dbFuncParamList())) {
				for (W5GlobalFuncParam p1 : r.getGlobalFunc().get_dbFuncParamList())
					if (p1.getOutFlag() == 0) {
						Object o = GenericUtil.prepareParam(p1, r.getScd(), r.getRequestParams(), (short) -1, null,
								(short) 0, p1.getSourceTip() == 1 ? p1.getDsc() : null, null, r.getErrorMap());
						if (o == null)
							params.add(null);
						else if ((o instanceof Integer) || (o instanceof Double) || (o instanceof BigDecimal)
								|| (o instanceof Boolean))
							params.add(o);
						else if ((o instanceof Date))
							params.add(GenericUtil.uFormatDate((Date) o));
						else
							params.add(o);
					} else
						hasOutParam = true;
			}

			try {
				Object funcResult = ((Invocable) nashornEngine).invokeFunction(fncName, params.toArray(new Object[0]));

				if (hasOutParam) {
					// JSONObject jo=new JSONObject();
					Map<String, String> res = new HashMap<String, String>();
					if (funcResult != null && funcResult instanceof ScriptObjectMirror) {
						Map resultMap = fromScriptObject2Map((ScriptObjectMirror) funcResult);
						for (W5GlobalFuncParam p1 : r.getGlobalFunc().get_dbFuncParamList())
							if (p1.getOutFlag() != 0 && resultMap.containsKey(p1.getDsc())) {
								Object em = resultMap.get(p1.getDsc());
								if (em != null) {
									String v = em.toString();
									if (p1.getParamTip() == 4 && v.endsWith(".0"))
										v = v.substring(0, v.length() - 2);
									res.put(p1.getDsc(), v);
								}
							}
					}
					r.setResultMap(res);
				}
			} catch (Exception ge) {
				dao.logGlobalFuncAction(action, r, error);
				throw new IWBException("rhino", "NashornGlobalFunc.Run", r.getGlobalFuncId(), script,
						"[20," + r.getGlobalFuncId() + "] " + r.getGlobalFunc().getDsc(), ge);
			}

			break;

		case 11:// GraalJS
			if (polyglot == null)
				polyglot = org.graalvm.polyglot.Context.newBuilder("js").allowHostAccess(true).build();
			Value func = (Value) FrameworkCache.getGraalFunc(scd, "20." + globalFuncId);
			if (func == null)
				try {
					StringBuilder sb = new StringBuilder();
					sb.append("(function($");
					if (!GenericUtil.isEmpty(r.getGlobalFunc().get_dbFuncParamList())) {
						for (W5GlobalFuncParam p1 : r.getGlobalFunc().get_dbFuncParamList())
							if (p1.getOutFlag() == 0) {
								sb.append(",").append(p1.getDsc());
							} else
								hasOutParam = true;
					}
					if (requestJson != null && requestJson instanceof JSONObject) {
						sb.append(",json");
						r.getRequestParams().remove("_json");
					} else
						requestJson = null;

					sb.append("){\n").append(script).append("\n})");
					script = sb.toString();
					func = polyglot.eval("js", script);
					FrameworkCache.addGraalFunc(scd, "20." + globalFuncId, func);
				} catch (Exception ge) {
					dao.logGlobalFuncAction(action, r, error);
					throw new IWBException("rhino", "GraalGlobalFunc.Compile", r.getGlobalFuncId(), script,
							"[20," + r.getGlobalFuncId() + "] " + r.getGlobalFunc().getDsc(), ge);
				}

			params.add(new GraalScript(r.getScd(), r.getRequestParams(), this));

			if (requestJson != null && requestJson instanceof JSONObject) {
				params.add(requestJson);
			} else if (!GenericUtil.isEmpty(r.getGlobalFunc().get_dbFuncParamList())) {
				for (W5GlobalFuncParam p1 : r.getGlobalFunc().get_dbFuncParamList())
					if (p1.getOutFlag() == 0) {
						Object o = GenericUtil.prepareParam(p1, r.getScd(), r.getRequestParams(), (short) -1, null,
								(short) 0, p1.getSourceTip() == 1 ? p1.getDsc() : null, null, r.getErrorMap());
						if (o == null)
							params.add(null);
						else if ((o instanceof Integer) || (o instanceof Double) || (o instanceof BigDecimal)
								|| (o instanceof Boolean))
							params.add(o);
						else if ((o instanceof Date))
							params.add(GenericUtil.uFormatDate((Date) o));
						else
							params.add(o);
					}
			}
			try {
				Object funcResult = func.execute(params.toArray(new Object[0]));
			} catch (Exception ge) {
				dao.logGlobalFuncAction(action, r, error);
				throw new IWBException("rhino", "GraalGlobalFunc.Run", r.getGlobalFuncId(), script,
						"[20," + r.getGlobalFuncId() + "] " + r.getGlobalFunc().getDsc(), ge);
			}

			break;
		case 111: // rhino code

			ContextFactory factory = RhinoContextFactory.getGlobal();
			Context cx = factory.enterContext();

			// Context cx = Context.enter();
			try {
				cx.setOptimizationLevel(-1);
				if (FrameworkSetting.rhinoInstructionCount > 0)
					cx.setInstructionObserverThreshold(FrameworkSetting.rhinoInstructionCount);
				// Initialize the standard objects (Object, Function, etc.)
				// This must be done before scripts can be executed. Returns
				// a scope object that we use in later calls.
				Scriptable scope = cx.initStandardObjects();

				// Collect the arguments into a single string.
				StringBuilder sc = new StringBuilder();

				if (!GenericUtil.isEmpty(r.getGlobalFunc().get_dbFuncParamList())) {
					sc.append("var ");
					for (W5GlobalFuncParam p1 : r.getGlobalFunc().get_dbFuncParamList())
						if (p1.getOutFlag() == 0) {
							if (sc.length() > 4)
								sc.append(", ");
							sc.append(p1.getDsc()).append("=");
							Object o = GenericUtil.prepareParam(p1, r.getScd(), r.getRequestParams(), (short) -1, null,
									(short) 0, p1.getSourceTip() == 1 ? p1.getDsc() : null, null, r.getErrorMap());
							if (o == null)
								sc.append("null");
							else if ((o instanceof Integer) || (o instanceof Double) || (o instanceof BigDecimal)
									|| (o instanceof Boolean))
								sc.append(o);
							else if ((o instanceof Date))
								sc.append("'").append(GenericUtil.uFormatDate((Date) o)).append("'");
							else
								sc.append("'").append(o).append("'");
						} else
							hasOutParam = true;
					if (sc.length() > 4)
						sc.append(";\n");
					else
						sc.setLength(0);
				}
				if (requestJson != null && requestJson instanceof JSONObject) {
					sc.append("var json=").append(((JSONObject) requestJson).toString()).append(";\n");
					r.getRequestParams().remove("_json");
				}
				if (script.contains("$.") || script.contains("$.")) {
					RhinoScript se = new RhinoScript(r.getScd(), r.getRequestParams(), this);
					Object wrappedOut = Context.javaToJS(se, scope);
					ScriptableObject.putProperty(scope, "$", wrappedOut);
				}
				sc.append("\nvar _scd=").append(GenericUtil.fromMapToJsonString2(r.getScd())).append(";\nvar _request=")
						.append(GenericUtil.fromMapToJsonString2(r.getRequestParams())).append(";\n").append(script);

				script = sc.toString();

				// Now evaluate the string we've colected.
				cx.evaluateString(scope, script, null, 1, null);
				/*
				 * if(scope.has("errorMsg", scope)){ Object em =
				 * RhinoUtil.rhinoValue(scope.get("errorMsg", scope)); if(em!=null)throw new
				 * PromisException("rhino","GlobalFuncId", r.getGlobalFuncId(), script,
				 * LocaleMsgCache.get2(0,(String)r.getScd().get("locale"),em. toString()),
				 * null); }
				 */
				if (hasOutParam) {
					// JSONObject jo=new JSONObject();
					Map<String, String> res = new HashMap<String, String>();
					for (W5GlobalFuncParam p1 : r.getGlobalFunc().get_dbFuncParamList())
						if (p1.getOutFlag() != 0 && scope.has(p1.getDsc(), scope)) {
							Object em = RhinoUtil.rhinoValue(scope.get(p1.getDsc(), scope));
							if (em != null)
								res.put(p1.getDsc(), em.toString());
						}
					r.setResultMap(res);
				}
				if (scope.has("outMsgs", scope)) { // TODO
					Object em = scope.get("outMsgs", scope);
				}
				r.setSuccess(true);
			} catch (Exception e) {
				error = e.getMessage();
				throw new IWBException("rhino", "GlobalFunc", r.getGlobalFuncId(), script,
						"[20," + r.getGlobalFuncId() + "] " + r.getGlobalFunc().getDsc(), e);
			} finally {
				// Exit from the context.
				cx.exit();
				dao.logGlobalFuncAction(action, r, error);
			}
			break;
		case 99: // DB
			dao.executeDbFunc(r, "");

		}

		if (r.getErrorMap().isEmpty()) { // sorun yok
			// post sms
			if (!GenericUtil.isEmpty(r.getResultMap()))
				parameterMap.putAll(r.getResultMap()); // veli TODO
														// acaba
														// hata
														// olabilir
														// mi? baska
														// bir map'e
														// mi atsak
			// sadece burasi icin?
		}
		GlobalFuncTrigger.afterExec(r);

		switch (globalFuncId) {
		case -478: // reload locale msg cache
			for (Object[] m : (List<Object[]>) dao.executeSQLQuery(
					"select locale, locale_msg_key, dsc from iwb.w5_locale_msg where locale_msg_key=? AND customization_id=?",
					parameterMap.get("plocale_msg_key"), scd.get("customizationId"))) {
				LocaleMsgCache.set2((Integer) scd.get("customizationId"), (String) m[0], (String) m[1], (String) m[2]);
			}
		}

		return r;
	}

	public W5GlobalFuncResult postEditGridGlobalFunc(Map<String, Object> scd, int dbFuncId, int dirtyCount,
			Map<String, String> requestParams, String prefix) {

		W5GlobalFuncResult dbFuncResult = metaDataDao.getGlobalFuncResult(scd, dbFuncId);
		if (!GenericUtil.isEmpty(dbFuncResult.getGlobalFunc().getAccessSourceTypes())
				&& !GenericUtil.hasPartInside2(dbFuncResult.getGlobalFunc().getAccessSourceTypes(), 1))
			throw new IWBException("security", "DbProc", dbFuncId, null, "Access Restrict Type Control", null);
		if (acEngine.checkAccessRecordControlViolation(scd, 4, 20, "" + dbFuncId))
			throw new IWBException("security", "DbProc Execute3", dbFuncId, null, "Access Execute Control", null);

		dbFuncResult.setErrorMap(new HashMap());
		dbFuncResult.setRequestParams(requestParams);
		for (int id = 1; id <= dirtyCount; id++) {

			GlobalFuncTrigger.beforeExec(dbFuncResult);
			dao.executeDbFunc(dbFuncResult, prefix + id);
			GlobalFuncTrigger.afterExec(dbFuncResult);

			if (!dbFuncResult.getErrorMap().isEmpty() || !dbFuncResult.isSuccess()) {
				throw new IWBException("validation", "GlobalFunc", -dbFuncId, null, "Detail Grid Validation", null);
			}
		}

		return dbFuncResult;
	}

	public void executeTableEvent(W5TableEvent ta, W5FormResult formResult, String action, Map<String, Object> scd,
			Map<String, String> requestParams, W5Table t, String ptablePk) {

		if (useNashorn) {
			if (nashornEngine == null)
				nashornEngine = new ScriptEngineManager().getEngineByName("nashorn");
			Object nobj = FrameworkCache.getGraalFunc(scd, "1209." + ta.getTableTriggerId());

			String scrName = null, script = null;
			if (nobj == null)
				try {
					scrName = "te_" + (scd != null && scd.get("projectId") != null
							? scd.get("projectId").toString().replace('-', '_')
							: "xxx") + "_1209_" + ta.getTableTriggerId();
					StringBuilder sb = new StringBuilder();
					sb.append("function ").append(scrName).append("($, _scd, _request, triggerAction, ")
							.append(t.get_tableFieldList().get(0).getDsc());
					sb.append("){\n");
					sb.append(ta.getTriggerCode());
					if (ta.getTriggerCode().indexOf("result") > -1)
						sb.append("\nreturn result;");
					sb.append("\n}");
					script = sb.toString();
					nashornEngine.eval(script);
					FrameworkCache.addGraalFunc(scd, "1209." + ta.getTableTriggerId(), scrName);
				} catch (Exception ge) {
//				dao.logGlobalFuncAction(action, r, error);
					throw new IWBException("rhino", "NashornTableEvent.Compile", ta.getTableTriggerId(), script,
							"[1209," + ta.getTableTriggerId() + "] " + ta.getDsc(), ge);
				}
			else if (nobj instanceof String) {
				scrName = nobj.toString();
			}

			try {
				Object funcResult = ((Invocable) nashornEngine).invokeFunction(scrName,
						new NashornScript(scd, requestParams, this), scd, requestParams, action,
						GenericUtil.isEmpty(ptablePk) ? null : ptablePk);
				if (funcResult != null && funcResult instanceof Boolean && (Boolean) funcResult == false)
					funcResult = null;

				if (funcResult != null) {
					String msg = funcResult instanceof Boolean ? LocaleMsgCache.get2(scd, ta.getDsc())
							: funcResult.toString();
//					short resultAction = ta.getLkpResultAction();
//					if (scope.has("resultAction", scope))resultAction = (short) GenericUtil.uInt(scope.get("resultAction", scope).toString());
					switch (ta.getLkpResultAction()) {
					case 1: // readonly
						formResult.setViewMode(true);
					case 0: // continue
						formResult.getOutputMessages().add(msg);
						break;
					case 2: // confirm & continue
						if (!requestParams.containsKey("_confirmId_" + ta.getTableTriggerId()))
							throw new IWBException("confirm", "ConfirmId", ta.getTableTriggerId(), null, msg, null);
						break;
					case 3: // stop with message
						throw new IWBException("security", "TableTrigger", ta.getTableTriggerId(), null, msg, null);
					}
				}

			} catch (Exception ge) {
//				dao.logGlobalFuncAction(action, r, error);
				throw new IWBException("rhino", "NashornTableEvent.Run", ta.getTableTriggerId(), script,
						"[1209," + ta.getTableTriggerId() + "] " + ta.getDsc(), ge);
			}

		} else {
			ContextFactory factory = RhinoContextFactory.getGlobal();
			Context cx = factory.enterContext();

			// Context cx = Context.enter();
			StringBuilder sc = new StringBuilder();
			try {
				cx.setOptimizationLevel(-1);
				if (FrameworkSetting.rhinoInstructionCount > 0)
					cx.setInstructionObserverThreshold(FrameworkSetting.rhinoInstructionCount);
				// Initialize the standard objects (Object, Function,
				// etc.)
				// This must be done before scripts can be executed.
				// Returns
				// a scope object that we use in later calls.
				Scriptable scope = cx.initStandardObjects();
				if (ta.getTriggerCode().indexOf("$.") > -1) {
					RhinoScript se = new RhinoScript(scd, requestParams, this);
					Object wrappedOut = Context.javaToJS(se, scope);
					ScriptableObject.putProperty(scope, "$", wrappedOut);
				}
				// Collect the arguments into a single string.
				sc.append("\nvar _scd=").append(GenericUtil.fromMapToJsonString(scd));
				sc.append("\nvar _request=").append(GenericUtil.fromMapToJsonString(requestParams));
				sc.append("\nvar triggerAction='").append(action).append("';");
				if (!GenericUtil.isEmpty(ptablePk))
					sc.append("\n").append(t.get_tableFieldList().get(0).getDsc()).append("='").append(ptablePk)
							.append("';");
				sc.append("\n").append(ta.getTriggerCode());

				// sc.append("'})';");
				// Now evaluate the string we've colected.
				cx.evaluateString(scope, sc.toString(), null, 1, null);

				Object result = null;
				if (scope.has("result", scope))
					result = scope.get("result", scope);

				String msg = LocaleMsgCache.get2(scd, ta.getDsc());
				boolean b = false;
				if (result != null && result instanceof Undefined)
					result = null;
				else if (result != null && result instanceof Boolean)
					if ((Boolean) result == false)
						result = null;

				if (result != null) {
					msg = result.toString();
					short resultAction = ta.getLkpResultAction();
					if (scope.has("resultAction", scope))
						resultAction = (short) GenericUtil.uInt(scope.get("resultAction", scope).toString());
					switch (resultAction) {
					case 1: // readonly
						formResult.setViewMode(true);
					case 0: // continue
						formResult.getOutputMessages().add(msg);
						break;
					case 2: // confirm & continue
						if (!requestParams.containsKey("_confirmId_" + ta.getTableTriggerId()))
							throw new IWBException("confirm", "ConfirmId", ta.getTableTriggerId(), null, msg, null);
						break;
					case 3: // stop with message
						throw new IWBException("security", "TableTrigger", ta.getTableTriggerId(), null, msg, null);
					}
				}
			} catch (Exception e) {
				throw new IWBException("rhino", "TableEvent", ta.getTableTriggerId(), sc.toString(),
						"[1209," + ta.getTableTriggerId() + "] " + ta.getDsc(), e);
			} finally {
				// Exit from the context.
				cx.exit();
			}
		}
	}

	public Object executeScript(Map<String, Object> scd, Map<String, String> requestParams, String script, Map obj,
			String key) {
		if (GenericUtil.isEmpty(script))
			return null;

		if (useNashorn) {
			if (nashornEngine == null)
				nashornEngine = new ScriptEngineManager().getEngineByName("nashorn");
			Object nobj = FrameworkCache.getGraalFunc(scd, key);

			String fncName = null;
			if (nobj == null)
				try {
					fncName = "scr_" + (scd != null && scd.get("projectId") != null
							? scd.get("projectId").toString().replace('-', '_')
							: "xxx") + "_" + key;
					StringBuilder sb = new StringBuilder();
					sb.append("function ").append(fncName).append("($, _scd, _request, _obj");
					sb.append("){\n");
					sb.append(script);
					sb.append("\nreturn result}");
					script = sb.toString();
					nashornEngine.eval(script);
					FrameworkCache.addGraalFunc(scd, key, fncName);
				} catch (Exception ge) {
//				dao.logGlobalFuncAction(action, r, error);
					throw new IWBException("rhino", "NashornScript.Compile", 0, script, key, ge);
				}
			else if (nobj instanceof String) {
				fncName = nobj.toString();
			}

			try {
				Object funcResult = ((Invocable) nashornEngine).invokeFunction(fncName,
						new NashornScript(scd, requestParams, this), scd, requestParams, obj);

				return funcResult;

			} catch (Exception ge) {
//				dao.logGlobalFuncAction(action, r, error);
				throw new IWBException("rhino", "NashornScript.Run", 0, script, fncName, ge);
			}

		} else {
			ContextFactory factory = RhinoContextFactory.getGlobal();
			Context cx = factory.enterContext();

			// Context cx = Context.enter();
			try {
				cx.setOptimizationLevel(-1);
				if (FrameworkSetting.rhinoInstructionCount > 0)
					cx.setInstructionObserverThreshold(FrameworkSetting.rhinoInstructionCount);
				// Initialize the standard objects (Object, Function, etc.)
				// This must be done before scripts can be executed. Returns
				// a scope object that we use in later calls.
				Scriptable scope = cx.initStandardObjects();

				// Collect the arguments into a single string.
				RhinoScript se = new RhinoScript(scd, requestParams, this);
				Object wrappedOut = Context.javaToJS(se, scope);
				ScriptableObject.putProperty(scope, "$", wrappedOut);

				StringBuilder sc = new StringBuilder();

				if (obj != null)
					sc.append("var _obj=").append(GenericUtil.fromMapToJsonString2Recursive(obj)).append(";\n");
				if (scd != null)
					sc.append("var _scd=").append(GenericUtil.fromMapToJsonString2(scd)).append(";\n");
				if (requestParams != null)
					sc.append("var _request=").append(GenericUtil.fromMapToJsonString2(requestParams)).append(";\n");
				if (script.charAt(0) == '!')
					script = script.substring(1);
				sc.append(script);

				script = sc.toString();

				cx.evaluateString(scope, script, null, 1, null);
//				if (GenericUtil.isEmpty(result))
				String result = "result";
				Object res = null;
				if (scope.has(result, scope)) {
					res = scope.get(result, scope);
					if (res != null && res instanceof org.mozilla.javascript.Undefined)
						res = null;
				}
				return res;

			} catch (Exception e) {
				throw new IWBException("rhino", "RhinoJS", 0, script, e.getMessage(), e);
			} finally {
				// Exit from the context.
				cx.exit();
			}
		}
	}

	public void executeQueryAsScript(W5QueryResult qr, String code) {

		// Context cx = Context.enter();
		W5Query q = qr.getQuery();
		String script = GenericUtil.uStrNvl(code, q.getSqlFrom());
		List<Object> params = new ArrayList();

		if (useNashorn) { // Nashorn
			if (nashornEngine == null)
				nashornEngine = new ScriptEngineManager().getEngineByName("nashorn");
			Object nobj = FrameworkCache.getGraalFunc(qr.getScd(), "8." + qr.getQueryId());
			String qryName = null;
			if (nobj == null)
				try {
					qryName = "qry_" + qr.getScd().get("projectId").toString().replace('-', '_') + "_"
							+ qr.getQueryId();
					StringBuilder sb = new StringBuilder();
					sb.append("function ").append(qryName).append("($, _scd, _request");
					if (!GenericUtil.isEmpty(qr.getQuery().get_queryParams())) {
						for (W5QueryParam p1 : qr.getQuery().get_queryParams())
							sb.append(",").append(p1.getDsc());
					}

					sb.append("){\n");
					sb.append(script);
					sb.append("\nreturn result}");
					script = sb.toString();
					nashornEngine.eval(script);
					FrameworkCache.addGraalFunc(qr.getScd(), "8." + qr.getQueryId(), qryName);
				} catch (Exception ge) {
//				dao.logGlobalFuncAction(action, r, error);
					throw new IWBException("rhino", "NashornQuery.Compile", qr.getQueryId(), script,
							"[8," + qr.getQueryId() + "] " + qr.getQuery().getDsc(), ge);
				}
			else if (nobj instanceof String) {
				qryName = nobj.toString();
			}

			params.add(new NashornScript(qr.getScd(), qr.getRequestParams(), this));
			params.add(qr.getScd());
			params.add(qr.getRequestParams());

			for (W5QueryParam p1 : q.get_queryParams()) {
				String s = qr.getRequestParams().get(p1.getDsc());
				Object o = GenericUtil.isEmpty(s) ? null : GenericUtil.getObjectByTip(s, p1.getParamTip());
				if (o == null) {
					if (p1.getNotNullFlag() != 0)
						qr.getErrorMap().put(p1.getDsc(),
								LocaleMsgCache.get2(qr.getScd(), "validation_error_not_null"));
					params.add(null);
				} else if ((o instanceof Integer) || (o instanceof Double) || (o instanceof BigDecimal)
						|| (o instanceof Boolean))
					params.add(o);
				else if ((o instanceof Date))
					params.add(GenericUtil.uFormatDateTime((Date) o));
				else
					params.add(o);
			}

			try {
				Object funcResult = ((Invocable) nashornEngine).invokeFunction(qryName, params.toArray(new Object[0]));

				if (funcResult instanceof ScriptObjectMirror) {
					ScriptObjectMirror result = (ScriptObjectMirror) funcResult;
					if (!result.isArray()) { // result and extraOutMap:TODO
						if (result.containsKey("extraOutMap"))
							qr.setExtraOutMap(fromScriptObject2Map((ScriptObjectMirror) result.get("extraOutMap")));

						if (result.containsKey("data"))
							result = (ScriptObjectMirror) result.get("data");
						else if (result.containsKey("result"))
							result = (ScriptObjectMirror) result.get("result");
						else
							throw new IWBException("rhino", "NashornQuery.Typo", qr.getQueryId(), script,
									"[8," + qr.getQueryId() + "] " + qr.getQuery().getDsc() + " Missing data/result",
									null);
					}

//					int qi = result.size();
					qr.setFetchRowCount(result.size());

					int maxTabOrder = 0;
					qr.setNewQueryFields(new ArrayList());
					for (W5QueryField qf : q.get_queryFields()) {
						if (qf.getTabOrder() > maxTabOrder)
							maxTabOrder = qf.getTabOrder();
						qr.getNewQueryFields().add(qf);
					}
					for (W5QueryField qf : q.get_queryFields())
						if ((qf.getPostProcessTip() == 16 || qf.getPostProcessTip() == 17)
								&& qf.getLookupQueryId() != 0) {
							W5QueryResult queryFieldLookupQueryResult = metaDataDao.getQueryResult(qr.getScd(),
									qf.getLookupQueryId());
							if (queryFieldLookupQueryResult != null && queryFieldLookupQueryResult.getQuery() != null) {
								W5QueryField field = new W5QueryField();
								field.setDsc(qf.getDsc() + "_qw_");
								field.setMainTableFieldId(qf.getMainTableFieldId());
								maxTabOrder++;
								field.setTabOrder((short) maxTabOrder);
								qr.getNewQueryFields().add(field);
								if (qf.getPostProcessTip() == 16
										&& queryFieldLookupQueryResult.getQuery().get_queryFields().size() > 2)
									for (int qi = 2; qi < queryFieldLookupQueryResult.getQuery().get_queryFields()
											.size(); qi++) {
										W5QueryField qf2 = queryFieldLookupQueryResult.getQuery().get_queryFields()
												.get(qi);
										field = new W5QueryField();
										field.setDsc(qf.getDsc() + "__" + qf2.getDsc());
										field.setMainTableFieldId(qf2.getMainTableFieldId());
										maxTabOrder++;
										field.setTabOrder((short) maxTabOrder);
										qr.getNewQueryFields().add(field);
									}
							}
						}
					qr.setData(new ArrayList(result.size()));
					int qi = 0;
					for (Object noo : result.values()) {
						ScriptObjectMirror no = (ScriptObjectMirror) noo;
						Object[] o = new Object[maxTabOrder];
						qr.getData().add(o);
						for (W5QueryField qf : qr.getNewQueryFields())
							if (no.containsKey(qf.getDsc())) {
								Object o2 = no.get(qf.getDsc());
								if (o2 != null) {
									switch (qf.getFieldTip()) {
									case 4: // integer
										o[qf.getTabOrder() - 1] = GenericUtil.uInt(o2);
										break;
									case 8: // json
										if (o2 instanceof ScriptObjectMirror) {
											ScriptObjectMirror no2 = (ScriptObjectMirror) o2;
											if (no2.isArray()) {
												o[qf.getTabOrder() - 1] = GenericUtil
														.fromListToJsonString2Recursive(fromScriptObject2List(no2));
											} else {
												o[qf.getTabOrder() - 1] = GenericUtil
														.fromMapToJsonString2Recursive(fromScriptObject2Map(no2));
											}

										} else if (o2 instanceof Map)
											o[qf.getTabOrder() - 1] = GenericUtil
													.fromMapToJsonString2Recursive((Map) o2);
										else if (o2 instanceof List)
											o[qf.getTabOrder() - 1] = GenericUtil
													.fromListToJsonString2Recursive((List) o2);
										else
											o[qf.getTabOrder() - 1] = "'" + GenericUtil.stringToJS(o2.toString()) + "'";

										break;
									default:
										o[qf.getTabOrder() - 1] = o2;
									}
								}
							}
						qi++;
					}
					qi++;
				}

			} catch (Exception ge) {
//				dao.logGlobalFuncAction(action, r, error);
				throw new IWBException("rhino", "NashornQuery.Run", qr.getQueryId(), script,
						"[8," + qr.getQueryId() + "] " + qr.getQuery().getDsc(), ge);
			}
		} else {
			ContextFactory factory = RhinoContextFactory.getGlobal();
			Context cx = factory.enterContext();
			try { // rhino
				cx.setOptimizationLevel(-1);
				if (FrameworkSetting.rhinoInstructionCount > 0)
					cx.setInstructionObserverThreshold(FrameworkSetting.rhinoInstructionCount);
				// Initialize the standard objects (Object, Function, etc.)
				// This must be done before scripts can be executed. Returns
				// a scope object that we use in later calls.
				Scriptable scope = cx.initStandardObjects();

				if (script.charAt(0) == '!')
					script = script.substring(1);
				// Collect the arguments into a single string.
				RhinoScript se = new RhinoScript(qr.getScd(), qr.getRequestParams(), this);
				Object wrappedOut = Context.javaToJS(se, scope);
				ScriptableObject.putProperty(scope, "$", wrappedOut);

				StringBuilder sc = new StringBuilder();
				boolean hasOutParam = false;
				if (q.get_queryParams().size() > 0) {
					sc.append("var ");
					for (W5QueryParam p1 : q.get_queryParams()) {
						if (sc.length() > 4)
							sc.append(", ");
						sc.append(p1.getDsc()).append("=");
						String s = qr.getRequestParams().get(p1.getDsc());
						Object o = GenericUtil.isEmpty(s) ? null : GenericUtil.getObjectByTip(s, p1.getParamTip());
						if (o == null) {
							if (p1.getNotNullFlag() != 0)
								qr.getErrorMap().put(p1.getDsc(),
										LocaleMsgCache.get2(qr.getScd(), "validation_error_not_null"));
							sc.append("null");
						} else if ((o instanceof Integer) || (o instanceof Double) || (o instanceof BigDecimal)
								|| (o instanceof Boolean))
							sc.append(o);
						else if ((o instanceof Date))
							sc.append("'").append(GenericUtil.uFormatDateTime((Date) o)).append("'");
						else
							sc.append("'").append(o).append("'");
					}
					if (sc.length() > 4)
						sc.append(";\n");
					else
						sc.setLength(0);
				}

				if (!qr.getErrorMap().isEmpty()) {
					return;
				}
				sc.append("\nvar _scd=").append(GenericUtil.fromMapToJsonString2(qr.getScd()))
						.append(";\nvar _request=").append(GenericUtil.fromMapToJsonString2(qr.getRequestParams()))
						.append(";\n").append(script);

				script = sc.toString();

				cx.evaluateString(scope, script, null, 1, null);
				if (scope.has("result", scope)) {
					Object r = scope.get("result", scope);
					if (r != null) {
						if (r instanceof NativeArray) {
							NativeArray ar = (NativeArray) r;
							int maxTabOrder = 0;
							qr.setNewQueryFields(new ArrayList());
							for (W5QueryField qf : q.get_queryFields()) {
								if (qf.getTabOrder() > maxTabOrder)
									maxTabOrder = qf.getTabOrder();
								qr.getNewQueryFields().add(qf);
							}
							for (W5QueryField qf : q.get_queryFields())
								if ((qf.getPostProcessTip() == 16 || qf.getPostProcessTip() == 17)
										&& qf.getLookupQueryId() != 0) {
									W5QueryResult queryFieldLookupQueryResult = metaDataDao.getQueryResult(qr.getScd(),
											qf.getLookupQueryId());
									if (queryFieldLookupQueryResult != null
											&& queryFieldLookupQueryResult.getQuery() != null) {
										W5QueryField field = new W5QueryField();
										field.setDsc(qf.getDsc() + "_qw_");
										field.setMainTableFieldId(qf.getMainTableFieldId());
										maxTabOrder++;
										field.setTabOrder((short) maxTabOrder);
										qr.getNewQueryFields().add(field);
										if (qf.getPostProcessTip() == 16
												&& queryFieldLookupQueryResult.getQuery().get_queryFields().size() > 2)
											for (int qi = 2; qi < queryFieldLookupQueryResult.getQuery()
													.get_queryFields().size(); qi++) {
												W5QueryField qf2 = queryFieldLookupQueryResult.getQuery()
														.get_queryFields().get(qi);
												field = new W5QueryField();
												field.setDsc(qf.getDsc() + "__" + qf2.getDsc());
												field.setMainTableFieldId(qf2.getMainTableFieldId());
												maxTabOrder++;
												field.setTabOrder((short) maxTabOrder);
												qr.getNewQueryFields().add(field);
											}
									}
								}
							qr.setData(new ArrayList((int) ar.getLength()));
							for (int qi = 0; qi < ar.getLength(); qi++) {
								NativeObject no = (NativeObject) (ar.get(qi, scope));
								Object[] o = new Object[maxTabOrder];
								qr.getData().add(o);
								for (W5QueryField qf : qr.getNewQueryFields())
									if (no.has(qf.getDsc(), scope)) {
										Object o2 = no.get(qf.getDsc(), scope);
										if (o2 != null) {
											if (o2 instanceof NativeJavaObject) {
												o2 = ((NativeJavaObject) o2).unwrap();
											}
											switch (qf.getFieldTip()) {
											case 4: // integer
												o[qf.getTabOrder() - 1] = GenericUtil.uInt(RhinoUtil.rhinoValue(o2));
												break;
											case 8: // json
												if (o2 instanceof NativeArray) {
													NativeArray no2 = (NativeArray) o2;
													o[qf.getTabOrder() - 1] = RhinoUtil
															.fromNativeArrayToJsonString2Recursive(no2);
												} else if (o2 instanceof NativeObject) {
													NativeObject no2 = (NativeObject) o2;
													o[qf.getTabOrder() - 1] = RhinoUtil
															.fromNativeObjectToJsonString2Recursive(no2);
												} else if (o2 instanceof Map)
													o[qf.getTabOrder() - 1] = GenericUtil
															.fromMapToJsonString2Recursive((Map) o2);
												else if (o2 instanceof List)
													o[qf.getTabOrder() - 1] = GenericUtil
															.fromListToJsonString2Recursive((List) o2);
												else
													o[qf.getTabOrder() - 1] = "'"
															+ GenericUtil.stringToJS(o2.toString()) + "'";
												break;
											default:
												o[qf.getTabOrder() - 1] = o2;
											}
										}
									}
							}
							qr.setFetchRowCount((int) ar.getLength());
							if (scope.has("extraOutMap", scope)) {
								Map extraOutMap = new HashMap();
								extraOutMap.put("rhino", scope.get("extraOutMap", scope));
								qr.setExtraOutMap(extraOutMap);
							}
						}
					}
				} else
					return;

			} catch (Exception e) {
				throw new IWBException("rhino", "Query", q.getQueryId(), script, "[8," + q.getQueryId() + "]", e);
			} finally {
				// Exit from the context.
				cx.exit();
			}
		}
	}

	public Map executeQuery4StatWS(W5QueryResult queryResult) {

		W5WsMethod wsm = FrameworkCache.getWsMethod(queryResult.getScd(), queryResult.getQuery().getMainTableId());
		Map<String, Object> scd = queryResult.getScd();
		/*
		 * if (wsm.get_params() == null) { wsm.set_params(
		 * find("from W5WsMethodParam t where t.wsMethodId=? AND t.projectUuid=? order by t.tabOrder"
		 * , wsm.getWsMethodId(), scd.get("projectIdId"))); wsm.set_paramMap(new
		 * HashMap()); for (W5WsMethodParam wsmp : wsm.get_params())
		 * wsm.get_paramMap().put(wsmp.getWsMethodParamId(), wsmp); }
		 */
		W5WsMethodParam parentParam = null;
		for (W5WsMethodParam px : wsm.get_params())
			if (px.getOutFlag() != 0 && px.getParamTip() == 10) {
				parentParam = px;
				break;
			}
		Map<String, String> m2 = new HashMap();
		Map<String, String> requestParams = queryResult.getRequestParams();

		int statType = GenericUtil.uInt(requestParams, "_stat"); // 0:count,
																	// 1:sum,
																	// 2.avg
		String funcFields = requestParams.get("_ffids"); // statFunctionFields
		if (statType > 0 && GenericUtil.isEmpty(funcFields))
			throw new IWBException("framework", "Query", queryResult.getQueryId(), null,
					LocaleMsgCache.get2(0, (String) scd.get("locale"), "fw_grid_stat_func_fields"), null);

		int queryFieldId = GenericUtil.uInt(requestParams, "_qfid");
		int stackFieldId = GenericUtil.uInt(requestParams, "_sfid");
		if (stackFieldId > 0 && stackFieldId == queryFieldId)
			stackFieldId = 0;

		for (W5QueryParam qp : queryResult.getQuery().get_queryParams())
			if (!GenericUtil.isEmpty(requestParams.get(qp.getDsc()))) {
				m2.put(qp.getExpressionDsc(), requestParams.get(qp.getDsc()));
			}
		StringBuilder rc = new StringBuilder();
		rc.append("function _x_(x){\nreturn {").append(queryResult.getQuery().getSqlSelect())
				.append("\n}}\nvar result=[], q=$.REST('").append(wsm.get_ws().getDsc() + "." + wsm.getDsc())
				.append("',").append(GenericUtil.fromMapToJsonString2(m2))
				.append(");\nif(q && q.get('success')){q=q.get('").append(parentParam.getDsc())
				.append("');for(var i=0;i<q.size();i++)result.push(_x_(q.get(i)));}");
		executeQueryAsScript(queryResult, rc.toString());
		Map result = new HashMap();
		result.put("success", true);
		if (queryResult.getErrorMap().isEmpty()) {
			List<Map> data = new ArrayList();
			Map<String, Map> mdata = new HashMap();
			W5QueryField statQF = null, funcQF = null;
			W5LookUp statLU = null;
			for (W5QueryField qf : queryResult.getQuery().get_queryFields())
				if (qf.getQueryFieldId() == queryFieldId) {
					statQF = qf;
					if (qf.getPostProcessTip() == 10)
						statLU = FrameworkCache.getLookUp(scd, qf.getLookupQueryId());
					break;
				}
			if (statType > 0) {
				int funcFieldId = GenericUtil.uInt(funcFields.split(",")[0]);
				for (W5QueryField qf : queryResult.getQuery().get_queryFields())
					if (qf.getQueryFieldId() == funcFieldId) {
						funcQF = qf;
						break;
					}
			}
			for (Object[] o : queryResult.getData()) {
				Object okey = o[statQF.getTabOrder() - 1];
				String key = "_";
				if (okey != null)
					key = okey.toString();
				Map mr = mdata.get(key);
				if (mr == null) {
					mr = new HashMap();
					mr.put("id", key);
					if (statLU != null) {
						W5LookUpDetay ld = statLU.get_detayMap().get(key.toString());
						mr.put("dsc", LocaleMsgCache.get2(scd, ld.getDsc()));
					} else
						mr.put("dsc", key);
					mr.put("xres", BigDecimal.ZERO);
					mr.put("xcnt", 0);
					mdata.put(key, mr);
					data.add(mr);
				}
				switch (statType) {
				case 0: // count
				case 2: // avg
					int cnt = (Integer) mr.get("xcnt");
					cnt++;
					mr.put("xcnt", cnt);
					if (statType == 0)
						break;
				case 1: // sum
					BigDecimal res = (BigDecimal) mr.get("xres");
					res = res.add(GenericUtil.uBigDecimal2(o[funcQF.getTabOrder() - 1]));
					mr.put("xres", res);
					break;
				}
			}
			for (Map mr : data)
				switch (statType) {
				case 0: // count
					mr.put("xres", mr.get("xcnt"));
					break;
				case 2: // avg
					int cnt = (Integer) mr.get("xcnt");
					if (cnt > 0)
						mr.put("xres", ((BigDecimal) mr.get("xres")).divide(new BigDecimal(cnt)));
					break;
				}

			result.put("data", data);
		}

		return result;
	}

	public W5GlobalFuncResult executeGlobalFunc4Debug(Map<String, Object> scd, int dbFuncId,
			Map<String, String> parameterMap) {
		W5GlobalFuncResult r = dbFuncId == -1 ? new W5GlobalFuncResult(-1)
				: metaDataDao.getGlobalFuncResult(scd, dbFuncId);
		r.setScd(scd);
		r.setErrorMap(new HashMap());
		r.setRequestParams(parameterMap);
		String script = parameterMap.get("_rhino_script_code");

		if (useNashorn) {
			if (nashornEngine == null)
				nashornEngine = new ScriptEngineManager().getEngineByName("nashorn");
			String fncName = null;
			boolean hasOutParam = false;
			List<Object> params = new ArrayList();
			try {
				fncName = "dfnc_" + (r.getScd() != null && r.getScd().get("projectId") != null
						? r.getScd().get("projectId").toString().replace('-', '_')
						: "xxx") + "_" + Math.abs(dbFuncId);
				StringBuilder sb = new StringBuilder();
				StringBuilder sbPost1 = new StringBuilder(), sbPost2 = new StringBuilder();
				sb.append("function ").append(fncName).append("($, _scd, _request");
				if (r.getGlobalFunc() != null && !GenericUtil.isEmpty(r.getGlobalFunc().get_dbFuncParamList())) {
					for (W5GlobalFuncParam p1 : r.getGlobalFunc().get_dbFuncParamList())
						if (p1.getOutFlag() == 0) {
							sb.append(",").append(p1.getDsc());
						} else {
							hasOutParam = true;
							sbPost1.append(p1.getDsc()).append("=null, ");
							sbPost2.append(p1.getDsc()).append(":").append(p1.getDsc()).append(", ");
						}
				}

				sb.append("){\n");
				if (hasOutParam) {
					sbPost1.setLength(sbPost1.length() - 2);
					sbPost2.setLength(sbPost2.length() - 2);
					sb.append("var ").append(sbPost1).append(";\n").append(script).append("\nreturn {").append(sbPost2)
							.append("}");

				} else
					sb.append(script);
				sb.append("\n}");
				script = sb.toString();
				nashornEngine.eval(script);
			} catch (Exception ge) {
//				dao.logGlobalFuncAction(action, r, error);
				throw new IWBException("rhino", "NashornGlobalFuncDebug.Compile", r.getGlobalFuncId(), script,
						"[20," + r.getGlobalFuncId() + "] " + r.getGlobalFunc().getDsc(), ge);
			}
			NashornScript se = new NashornScript(r.getScd(), r.getRequestParams(), this); 
			
			params.add(se);
			params.add(scd);
			params.add(parameterMap);

			if (r.getGlobalFunc() != null && !GenericUtil.isEmpty(r.getGlobalFunc().get_dbFuncParamList())) {
				for (W5GlobalFuncParam p1 : r.getGlobalFunc().get_dbFuncParamList())
					if (p1.getOutFlag() == 0) {
						Object o = GenericUtil.prepareParam(p1, r.getScd(), r.getRequestParams(), (short) -1, null,
								(short) 0, p1.getSourceTip() == 1 ? p1.getDsc() : null, null, r.getErrorMap());
						if (o == null)
							params.add(null);
						else if ((o instanceof Integer) || (o instanceof Double) || (o instanceof BigDecimal)
								|| (o instanceof Boolean))
							params.add(o);
						else if ((o instanceof Date))
							params.add(GenericUtil.uFormatDate((Date) o));
						else
							params.add(o);
					} else
						hasOutParam = true;
			}

			try {
				if (FrameworkSetting.debug)
					se.console("start: " + (r.getGlobalFunc() != null ? r.getGlobalFunc().getDsc() : "new"), "DEBUG", "info");
				long startTm = System.currentTimeMillis();
				
				Object funcResult = ((Invocable) nashornEngine).invokeFunction(fncName, params.toArray(new Object[0]));
				r.setProcessTime((int) (System.currentTimeMillis() - startTm));
				if (FrameworkSetting.debug)
					se.console("end: " + (r.getGlobalFunc() != null ? r.getGlobalFunc().getDsc() : "new") + " in " + r.getProcessTime() +"ms", "DEBUG", "info");

				if (hasOutParam) {
					// JSONObject jo=new JSONObject();
					Map<String, String> res = new HashMap<String, String>();
					if (funcResult != null && funcResult instanceof ScriptObjectMirror) {
						Map resultMap = fromScriptObject2Map((ScriptObjectMirror) funcResult);
						for (W5GlobalFuncParam p1 : r.getGlobalFunc().get_dbFuncParamList())
							if (p1.getOutFlag() != 0 && resultMap.containsKey(p1.getDsc())) {
								Object em = resultMap.get(p1.getDsc());
								if (em != null) {
									String v = em.toString();
									if (p1.getParamTip() == 4 && v.endsWith(".0"))
										v = v.substring(0, v.length() - 2);
									res.put(p1.getDsc(), v);
								}
							}
					}
					r.setResultMap(res);
				}
			} catch (Exception ge) {
//				dao.logGlobalFuncAction(action, r, error);
				throw new IWBException("rhino", "NashornGlobalFuncDebug.Run", r.getGlobalFuncId(), script,
						"[20," + r.getGlobalFuncId() + "] " + r.getGlobalFunc().getDsc(), ge);
			}

		} else {
			ContextFactory factory = RhinoContextFactory.getGlobal();
			Context cx = factory.enterContext();

			// Context cx = Context.enter();
			try {
				cx.setOptimizationLevel(-1);
				if (FrameworkSetting.rhinoInstructionCount > 0)
					cx.setInstructionObserverThreshold(FrameworkSetting.rhinoInstructionCount);
				// Initialize the standard objects (Object, Function, etc.)
				// This must be done before scripts can be executed. Returns
				// a scope object that we use in later calls.
				Scriptable scope = cx.initStandardObjects();

				if (script.charAt(0) == '!')
					script = script.substring(1);
				// Collect the arguments into a single string.
				StringBuilder sc = new StringBuilder();

				RhinoScript se = new RhinoScript(r.getScd(), r.getRequestParams(), this);
				Object wrappedOut = Context.javaToJS(se, scope);
				ScriptableObject.putProperty(scope, "$", wrappedOut);

				boolean hasOutParam = false;
				if (dbFuncId != -1 && r.getGlobalFunc().get_dbFuncParamList().size() > 0) {
					sc.append("var ");
					for (W5GlobalFuncParam p1 : r.getGlobalFunc().get_dbFuncParamList())
						if (p1.getOutFlag() == 0) {
							if (sc.length() > 4)
								sc.append(", ");
							sc.append(p1.getDsc()).append("=");
							String s = parameterMap.get(p1.getDsc());
							Object o = GenericUtil.isEmpty(s) ? null : GenericUtil.getObjectByTip(s, p1.getParamTip());
							if (o == null) {
								if (p1.getNotNullFlag() != 0)
									r.getErrorMap().put(p1.getDsc(),
											LocaleMsgCache.get2(scd, "validation_error_not_null"));
								sc.append("null");
							} else if ((o instanceof Integer) || (o instanceof Double) || (o instanceof BigDecimal)
									|| (o instanceof Boolean))
								sc.append(o);
							else if ((o instanceof Date))
								sc.append("'").append(GenericUtil.uFormatDate((Date) o)).append("'");
							else
								sc.append("'").append(o).append("'");
						} else
							hasOutParam = true;
					if (sc.length() > 4)
						sc.append(";\n");
					else
						sc.setLength(0);
				}
				if (!r.getErrorMap().isEmpty()) {
					r.setSuccess(false);
					return r;
				}
				sc.append("\nvar _scd=").append(GenericUtil.fromMapToJsonString2(r.getScd())).append(";\nvar _request=")
						.append(GenericUtil.fromMapToJsonString2(r.getRequestParams())).append(";\n").append(script);

				script = sc.toString();

				if (FrameworkSetting.debug)
					se.console("start: " + (r.getGlobalFunc() != null ? r.getGlobalFunc().getDsc() : "new"), "DEBUG",
							"info");
				long startTm = System.currentTimeMillis();
				cx.evaluateString(scope, script, null, 1, null);
				r.setProcessTime((int) (System.currentTimeMillis() - startTm));
				// if(FrameworkSetting.debug)se.console("end: " +
				// (r.getGlobalFunc()!=null ?
				// r.getGlobalFunc().getDsc(): "new"),"DEBUG","success");
				/*
				 * if(scope.has("errorMsg", scope)){ Object em =
				 * RhinoUtil.rhinoValue(scope.get("errorMsg", scope)); if(em!=null)throw new
				 * PromisException("rhino","GlobalFuncId", r.getGlobalFuncId(), script,
				 * LocaleMsgCache.get2(0,(String)r.getScd().get("locale"),em. toString()),
				 * null); }
				 */
				if (hasOutParam) {
					// JSONObject jo=new JSONObject();
					Map<String, String> res = new HashMap<String, String>();
					r.setResultMap(res);
					for (W5GlobalFuncParam p1 : r.getGlobalFunc().get_dbFuncParamList())
						if (p1.getOutFlag() != 0 && scope.has(p1.getDsc(), scope)) {
							Object em = RhinoUtil.rhinoValue(scope.get(p1.getDsc(), scope));
							if (em != null)
								res.put(p1.getDsc(), em.toString());
						}
				}
				if (scope.has("outMsgs", scope)) { // TODO
					Object em = scope.get("outMsgs", scope);
				}
			} catch (Exception e) {
				throw new IWBException("rhino", "Debug Backend", r.getGlobalFuncId(), script,
						LocaleMsgCache.get2(0, (String) r.getScd().get("locale"), e.getMessage()), e);
			} finally {
				// Exit from the context.
				cx.exit();
			}
		}
		r.setSuccess(true);
		return r;
	}

	public Map executeQueryAsScript4Debug(W5QueryResult qr, String script) {

		// Context cx = Context.enter();
		W5Query q = qr.getQuery();
		Map m = new HashMap();
		m.put("success", true);
		List<Object> params = new ArrayList();

		if (useNashorn) {
			if (nashornEngine == null)
				nashornEngine = new ScriptEngineManager().getEngineByName("nashorn");
			Object nobj = FrameworkCache.getGraalFunc(qr.getScd(), "8." + qr.getQueryId());
			String qryName =  "dqry_" + qr.getScd().get("projectId").toString().replace('-', '_') + "_"
					+ Math.abs(qr.getQueryId());
			if (nobj == null)
				try {
					StringBuilder sb = new StringBuilder();
					sb.append("function ").append(qryName).append("($, _scd, _request");
					if (!GenericUtil.isEmpty(qr.getQuery().get_queryParams())) {
						for (W5QueryParam p1 : qr.getQuery().get_queryParams())
							sb.append(",").append(p1.getDsc());
					}

					sb.append("){\n");
					sb.append(script);
					sb.append("\nreturn result}");
					script = sb.toString();
					nashornEngine.eval(script);
				} catch (Exception ge) {
//				dao.logGlobalFuncAction(action, r, error);
					throw new IWBException("rhino", "DebugNashornQuery.Compile", qr.getQueryId(), script,
							"[8," + qr.getQueryId() + "] " + qr.getQuery().getDsc(), ge);
				}
			else if (nobj instanceof String) {
				qryName = nobj.toString();
			}

			NashornScript se = new NashornScript(qr.getScd(), qr.getRequestParams(), this); 
			params.add(se);
			params.add(qr.getScd());
			params.add(qr.getRequestParams());

			for (W5QueryParam p1 : q.get_queryParams()) {
				String s = qr.getRequestParams().get(p1.getDsc());
				Object o = GenericUtil.isEmpty(s) ? null : GenericUtil.getObjectByTip(s, p1.getParamTip());
				if (o == null) {
					if (p1.getNotNullFlag() != 0)
						qr.getErrorMap().put(p1.getDsc(),
								LocaleMsgCache.get2(qr.getScd(), "validation_error_not_null"));
					params.add(null);
				} else if ((o instanceof Integer) || (o instanceof Double) || (o instanceof BigDecimal)
						|| (o instanceof Boolean))
					params.add(o);
				else if ((o instanceof Date))
					params.add(GenericUtil.uFormatDateTime((Date) o));
				else
					params.add(o);
			}

			try {
				if (FrameworkSetting.debug)
					se.console("start: " + (q != null ? q.getDsc() : "new"), "DEBUG", "info");
				long startTm = System.currentTimeMillis();

				Object funcResult = ((Invocable) nashornEngine).invokeFunction(qryName, params.toArray(new Object[0]));
				qr.setProcessTime((int) (System.currentTimeMillis() - startTm));
				if (FrameworkSetting.debug)
					se.console("end: " + (q != null ? q.getDsc() : "new") + " in " + qr.getProcessTime() +"ms", "DEBUG", "info");

				if (funcResult instanceof ScriptObjectMirror) {
					ScriptObjectMirror result = (ScriptObjectMirror) funcResult;
					if (!result.isArray()) { // result and extraOutMap:TODO
						if (result.containsKey("extraOutMap"))
							qr.setExtraOutMap(fromScriptObject2Map((ScriptObjectMirror) result.get("extraOutMap")));

						if (result.containsKey("data"))
							result = (ScriptObjectMirror) result.get("data");
						else if (result.containsKey("result"))
							result = (ScriptObjectMirror) result.get("result");
						else
							throw new IWBException("rhino", "DebugNashornQuery.Typo", qr.getQueryId(), script,
									"[8," + qr.getQueryId() + "] " + qr.getQuery().getDsc() + " Missing data/result",
									null);
					}

//					int qi = result.size();
					qr.setFetchRowCount(result.size());

					int maxTabOrder = 0;
					qr.setNewQueryFields(new ArrayList());
					for (W5QueryField qf : q.get_queryFields()) {
						if (qf.getTabOrder() > maxTabOrder)
							maxTabOrder = qf.getTabOrder();
						qr.getNewQueryFields().add(qf);
					}

					List<Map> data = new ArrayList(result.size());
					int qi = 0;
					for (Object noo : result.values()) {
						ScriptObjectMirror no = (ScriptObjectMirror) noo;
						Object[] o = new Object[maxTabOrder];
						Map d = new HashMap();
						data.add(d);
						for (W5QueryField qf : qr.getNewQueryFields())
							if (no.containsKey(qf.getDsc())) {
								// o[qf.getTabOrder()-1] =
								// no.get(qf.getDsc(), scope);
								d.put(qf.getDsc(), no.get(qf.getDsc()));
							}
						qi++;
					}
					m.put("data", data);
					m.put("fetchTime", System.currentTimeMillis() - startTm);
					Map m2 = new HashMap();
					m2.put("startRow", 0);
					m2.put("fetchCount", result.size());
					m2.put("totalCount", result.size());
					m.put("browseInfo", m2);
					
					List fields = new ArrayList();
					for (W5QueryField qf : q.get_queryFields()) {
						Map d = new HashMap();
						d.put("name", qf.getDsc());
						switch (qf.getFieldTip()) {
						case 3:
							d.put("type", "int");
							break;
						case 4:
							d.put("type", "float");
							break;
						case 2:
							d.put("type", "date");
							break;
						}
						fields.add(d);
					}
					m.put("fields", fields);

				}

			} catch (Exception ge) {
//				dao.logGlobalFuncAction(action, r, error);
				throw new IWBException("rhino", "NashornQuery.Run", qr.getQueryId(), script,
						"[8," + qr.getQueryId() + "] " + qr.getQuery().getDsc(), ge);
			}
		} else {
			ContextFactory factory = RhinoContextFactory.getGlobal();
			Context cx = factory.enterContext();
			// String script = q.getSqlFrom();
			try {
				cx.setOptimizationLevel(-1);
				if (FrameworkSetting.rhinoInstructionCount > 0)
					cx.setInstructionObserverThreshold(FrameworkSetting.rhinoInstructionCount);
				// Initialize the standard objects (Object, Function, etc.)
				// This must be done before scripts can be executed. Returns
				// a scope object that we use in later calls.
				Scriptable scope = cx.initStandardObjects();

				if (script.charAt(0) == '!')
					script = script.substring(1);
				// Collect the arguments into a single string.
				RhinoScript se = new RhinoScript(qr.getScd(), qr.getRequestParams(), this);
				Object wrappedOut = Context.javaToJS(se, scope);
				ScriptableObject.putProperty(scope, "$", wrappedOut);

				StringBuilder sc = new StringBuilder();

				boolean hasOutParam = false;
				if (q.get_queryParams().size() > 0) {
					sc.append("var ");
					for (W5QueryParam p1 : q.get_queryParams()) {
						if (sc.length() > 4)
							sc.append(", ");
						sc.append(p1.getDsc()).append("=");
						String s = qr.getRequestParams().get(p1.getDsc());
						Object o = GenericUtil.isEmpty(s) ? null : GenericUtil.getObjectByTip(s, p1.getParamTip());
						if (o == null) {
							if (p1.getNotNullFlag() != 0)
								qr.getErrorMap().put(p1.getDsc(),
										LocaleMsgCache.get2(qr.getScd(), "validation_error_not_null"));
							sc.append("null");
						} else if ((o instanceof Integer) || (o instanceof Double) || (o instanceof BigDecimal)
								|| (o instanceof Boolean))
							sc.append(o);
						else if ((o instanceof Date))
							sc.append("'").append(GenericUtil.uFormatDate((Date) o)).append("'");
						else
							sc.append("'").append(o).append("'");
					}
					if (sc.length() > 4)
						sc.append(";\n");
					else
						sc.setLength(0);
				}
				if (!qr.getErrorMap().isEmpty()) {
					throw new IWBException("rhino", "QueryId", q.getQueryId(), script,
							"Validation ERROR: " + GenericUtil.fromMapToJsonString2(qr.getErrorMap()), null);
				}
				sc.append("\nvar _scd=").append(GenericUtil.fromMapToJsonString2(qr.getScd()))
						.append(";\nvar _request=").append(GenericUtil.fromMapToJsonString2(qr.getRequestParams()))
						.append(";\n").append(script);

				script = sc.toString();

				se.console("Start: " + (q.getDsc() != null ? q.getDsc() : "new"), "iWB-QUERY-DEBUG", "warn");
				long startTm = System.currentTimeMillis();
				cx.evaluateString(scope, script, null, 1, null);
				m.put("execTime", System.currentTimeMillis() - startTm);
				se.console("End: " + (q.getDsc() != null ? q.getDsc() : "new"), "iWB-QUERY-DEBUG", "warn");
				startTm = System.currentTimeMillis();
				List data = null;
				if (scope.has("result", scope)) {
					Object r = scope.get("result", scope);
					if (r != null) {
						if (r instanceof NativeArray) {
							NativeArray ar = (NativeArray) r;
							int maxTabOrder = 0;
							for (W5QueryField qf : q.get_queryFields()) {
								if (qf.getTabOrder() > maxTabOrder)
									maxTabOrder = qf.getTabOrder();
							}
							qr.setNewQueryFields(q.get_queryFields());
							data = new ArrayList((int) ar.getLength());
							for (int qi = 0; qi < ar.getLength(); qi++) {
								NativeObject no = (NativeObject) (ar.get(qi, scope));
								Object[] o = new Object[maxTabOrder];
								Map d = new HashMap();
								data.add(d);
								for (W5QueryField qf : q.get_queryFields())
									if (no.has(qf.getDsc(), scope)) {
										// o[qf.getTabOrder()-1] =
										// no.get(qf.getDsc(), scope);
										d.put(qf.getDsc(), RhinoUtil.rhinoValue(no.get(qf.getDsc(), scope)));
									}
							}
							m.put("fetchTime", System.currentTimeMillis() - startTm);
							Map m2 = new HashMap();
							m2.put("startRow", 0);
							m2.put("fetchCount", (int) ar.getLength());
							m2.put("totalCount", (int) ar.getLength());
							m.put("browseInfo", m2);
						}
					}
				}
				if (data == null)
					throw new IWBException("rhino", "QueryId", q.getQueryId(), script, "[result] object not found",
							null);
				m.put("data", data);
				List fields = new ArrayList();
				for (W5QueryField qf : q.get_queryFields()) {
					Map d = new HashMap();
					d.put("name", qf.getDsc());
					switch (qf.getFieldTip()) {
					case 3:
						d.put("type", "int");
						break;
					case 4:
						d.put("type", "float");
						break;
					case 2:
						d.put("type", "date");
						break;
					}
					fields.add(d);
				}
				m.put("fields", fields);
			} catch (Exception e) {
				throw new IWBException("rhino", "Debug Query", q.getQueryId(), script,
						LocaleMsgCache.get2(0, (String) qr.getScd().get("locale"), e.getMessage()), e);
			} finally {
				// Exit from the context.
				cx.exit();
			}
		}
		return m;
	}

}
