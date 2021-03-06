package iwb.domain.db;

import java.util.List;
import java.util.Map;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Transient;

import org.hibernate.annotations.Immutable;

import iwb.util.GenericUtil;

// Generated Jun 17, 2007 5:12:14 PM by Hibernate Tools 3.2.0.b9


@Entity
@Immutable
@Table(name="w5_conversion",schema="iwb")
public class W5Conversion implements java.io.Serializable, W5Base {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1991000023L;
	private int conversionId;
	private String dsc;
	private int srcTableId;
	private int dstTableId;
	private int srcFormId;
	private int dstFormId;
	private short rowErrorStrategyType;
	private short previewFlag;
	private short synchOnUpdateFlag;
	private short conversionType;
	private short tabOrder;
	private short srcDstTip;
	private String actionTypes;
	private short activeFlag;
	private short maxNumofConversion;
	private short onSynchConditionAction;
	private short onDeleteAction;
	private String conditionSqlCode;
	private String rhinoCode;
	
	private	List<W5ConversionCol> _conversionColList;
	private	Map<Integer,W5ConversionCol> _conversionColMap;
	

	
	@Id
	@Column(name="conversion_id")
	public int getConversionId() {
		return conversionId;
	}
	public void setConversionId(int conversionId) {
		this.conversionId = conversionId;
	}
	
	@Column(name="src_table_id")
	public int getSrcTableId() {
		return srcTableId;
	}
	public void setSrcTableId(int srcTableId) {
		this.srcTableId = srcTableId;
	}
	
	@Column(name="dst_table_id")
	public int getDstTableId() {
		return dstTableId;
	}
	public void setDstTableId(int dstTableId) {
		this.dstTableId = dstTableId;
	}
	
	@Column(name="row_error_strategy_tip")
	public short getRowErrorStrategyType() {
		return rowErrorStrategyType;
	}
	public void setRowErrorStrategyType(short rowErrorStrategyType) {
		this.rowErrorStrategyType = rowErrorStrategyType;
	}
	@Transient
	public List<W5ConversionCol> get_conversionColList() {
		return _conversionColList;
	}
	public void set_conversionColList(List<W5ConversionCol> conversionColList) {
		_conversionColList = conversionColList;
	}
	@Transient
	public Map<Integer, W5ConversionCol> get_conversionColMap() {
		return _conversionColMap;
	}
	public void set_conversionColMap(Map<Integer, W5ConversionCol> conversionColMap) {
		_conversionColMap = conversionColMap;
	}

	@Column(name="preview_flag")
	public short getPreviewFlag() {
		return previewFlag;
	}
	public void setPreviewFlag(short previewFlag) {
		this.previewFlag = previewFlag;
	}
	@Column(name="synch_on_update_flag")
	public short getSynchOnUpdateFlag() {
		return synchOnUpdateFlag;
	}
	public void setSynchOnUpdateFlag(short synchOnUpdateFlag) {
		this.synchOnUpdateFlag = synchOnUpdateFlag;
	}

	@Column(name="src_form_id")
	public int getSrcFormId() {
		return srcFormId;
	}
	public void setSrcFormId(int srcFormId) {
		this.srcFormId = srcFormId;
	}
	@Column(name="dst_form_id")
	public int getDstFormId() {
		return dstFormId;
	}
	public void setDstFormId(int dstFormId) {
		this.dstFormId = dstFormId;
	}
	@Column(name="conversion_tip")
	public short getConversionType() {
		return conversionType;
	}
	public void setConversionType(short conversionType) {
		this.conversionType = conversionType;
	}
	@Column(name="tab_order")
	public short getTabOrder() {
		return tabOrder;
	}
	public void setTabOrder(short tabOrder) {
		this.tabOrder = tabOrder;
	}
	@Column(name="dsc")
	public String getDsc() {
		return dsc;
	}
	public void setDsc(String dsc) {
		this.dsc = dsc;
	}
	
	@Column(name="action_tips")
	public String getActionTypes() {
		return actionTypes;
	}
	public void setActionTypes(String actionTypes) {
		this.actionTypes = actionTypes;
	}
	@Column(name="active_flag")
	public short getActiveFlag() {
		return activeFlag;
	}
	public void setActiveFlag(short activeFlag) {
		this.activeFlag = activeFlag;
	}
	
	@Column(name="max_num_of_conversion")
	public short getMaxNumofConversion() {
		return maxNumofConversion;
	}
	public void setMaxNumofConversion(short maxNumofConversion) {
		this.maxNumofConversion = maxNumofConversion;
	}
	
	@Transient
	public boolean safeEquals(W5Base q){
		if(q==null)return false;
		W5Conversion c = (W5Conversion)q;
		boolean b = this.conversionId == c.getConversionId() &&
			GenericUtil.safeEquals(this.dsc , c.getDsc()) &&
			GenericUtil.safeEquals(this.conditionSqlCode , c.getConditionSqlCode()) &&
			this.srcTableId == c.getSrcTableId() &&
			this.dstTableId == c.getDstTableId() &&
			this.srcFormId == c.getSrcFormId() &&
			this.dstFormId == c.getDstFormId() &&
			this.rowErrorStrategyType == c.getRowErrorStrategyType() &&
			this.previewFlag == c.getPreviewFlag() &&
			this.synchOnUpdateFlag == c.getSynchOnUpdateFlag() &&
			this.conversionType == c.getConversionType() &&
			this.tabOrder == c.getTabOrder() &&
			this.actionTypes == c.getActionTypes() &&
			this.activeFlag == c.getActiveFlag() &&
			this.maxNumofConversion == c.getMaxNumofConversion();
		if(!b)return false;
		if(!GenericUtil.safeEquals(this._conversionColList, c._conversionColList))return false;
		return true;
	}
	@Column(name="condition_sql_code")
	public String getConditionSqlCode() {
		return conditionSqlCode;
	}
	
	public void setConditionSqlCode(String conditionSqlCode) {
		this.conditionSqlCode = conditionSqlCode;
	}
	

	@Column(name="on_synch_condition_action")
	public short getOnSynchConditionAction() {
		return onSynchConditionAction;
	}
	public void setOnSynchConditionAction(short onSynchConditionAction) {
		this.onSynchConditionAction = onSynchConditionAction;
	}
	@Column(name="on_delete_action")
	public short getOnDeleteAction() {
		return onDeleteAction;
	}
	public void setOnDeleteAction(short onDeleteAction) {
		this.onDeleteAction = onDeleteAction;
	}
	@Column(name="src_dst_tip")
	public short getSrcDstTip() {
		return srcDstTip;
	}
	public void setSrcDstTip(short srcDstTip) {
		this.srcDstTip = srcDstTip;
	}
	@Column(name="rhino_code")
	public String getRhinoCode() {
		return rhinoCode;
	}
	public void setRhinoCode(String rhinoCode) {
		this.rhinoCode = rhinoCode;
	}
	
	private String projectUuid;

	@Id
	@Column(name="project_uuid")
	public String getProjectUuid() {
		return projectUuid;
	}

	public void setProjectUuid(String projectUuid) {
		this.projectUuid = projectUuid;
	}

	public boolean equals(Object o) {
		if(o==null || !(o instanceof W5Conversion))return false;
		W5Conversion c = (W5Conversion)o;
		return c!=null && c.getConversionId()==getConversionId() && c.getProjectUuid().equals(projectUuid);
	}
	
	public int hashCode() {
		return projectUuid.hashCode() + 100*getConversionId();
	}	
}
