package iwb.timer;

import java.util.List;
import java.util.Map;
import java.util.TimerTask;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

import iwb.cache.FrameworkCache;
import iwb.cache.FrameworkSetting;
import iwb.domain.db.W5JobSchedule;
import iwb.domain.db.W5Project;
import iwb.domain.db.W5Workflow;
import iwb.domain.db.W5WorkflowRecord;
import iwb.domain.db.W5WorkflowStep;
import iwb.service.FrameworkService;
import iwb.util.GenericUtil;

public class GenericTimer extends TimerTask {
	
	
	public GenericTimer(TaskExecutor taskExecutor, FrameworkService service) {
		super();
		this.taskExecutor = taskExecutor;
		this.service = service;
	}

	private FrameworkService service;
	
	private TaskExecutor taskExecutor;

	private void checkJobs(){
		for(String projectId:FrameworkCache.wJobs.keySet()) {
			W5Project po = FrameworkCache.getProject(projectId);
			if(po!=null && (po.getCustomizationId()==0 || po.getCustomizationId()==140 || !FrameworkSetting.cloud)) {
				Map<Integer, W5JobSchedule> miv = FrameworkCache.wJobs.get(projectId);
				if(miv!=null)for(W5JobSchedule j:miv.values()) {
					if(j.runCheck()) { //
						taskExecutor.execute(new Runnable() {
				            @Override
				            public void run() {
				            	System.out.println("Start Job: " + j.getDsc());
				            	service.runJob(j);
				            }
				        });
					}
				}
			}
			
		}

	}

	public void run() {
		System.out.println("timer.run");
		checkJobs();
		if(FrameworkSetting.workflow)checkWorkflowEscalations();
	}

	private void checkWorkflowEscalations() {
		for(String projectId:FrameworkCache.wWorkflows.keySet()) {
			W5Project po = FrameworkCache.getProject(projectId);
			if(po!=null && (po.getCustomizationId()==0 || po.getCustomizationId()==140 || !FrameworkSetting.cloud)) {
				Map<Integer, W5Workflow> miw = FrameworkCache.wWorkflows.get(projectId);
				if(miw!=null)for(W5Workflow w:miw.values()) if(w.get_approvalStepList()!=null) {
					for(W5WorkflowStep step:w.get_approvalStepList())if(step.getTimeLimitFlag()!=0 && step.getTimeLimitDuration()>0){
						if(GenericUtil.isEmpty(step.getOnEscalationCode())) {
							W5WorkflowStep nextStep = w.get_approvalStepMap().get(step.getOnTimeLimitExceedStepId());
							if(nextStep!=null) {
				            	System.out.println("Update Escalations: " + w.getDsc());
								service.updateWorkflowEscalatedRecords(step, nextStep);
							}
						} else {
							List<W5WorkflowRecord> escalatedRecords = service.listWorkflowEscalatedRecords(step);
							for(W5WorkflowRecord record:escalatedRecords)
							taskExecutor.execute(new Runnable() {
					            @Override
					            public void run() {
					            	System.out.println("Start Escalations: " + w.getDsc());
					            	service.updateWorkflowEscalatedRecord(step, record);
					            }
					        });
						}
					}
				}
			}
			
		}
		
	}
}
