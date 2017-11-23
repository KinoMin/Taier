package com.dtstack.rdos.engine.execution.base;

import com.dtstack.rdos.engine.execution.base.enumeration.RdosTaskStatus;
import com.dtstack.rdos.engine.execution.base.pojo.JobResult;
import com.dtstack.rdos.engine.execution.base.pojo.ParamAction;
import java.io.IOException;
import java.util.Properties;

/**
 * Reason:
 * Date: 2017/2/20
 * Company: www.dtstack.com
 *
 * @ahthor xuchao
 */

public interface IClient {

    /**
     * FIXME 根据zk做初始化的时候的操作
     */
    void init(Properties prop) throws Exception;

    JobResult submitJob(JobClient jobClient);

    JobResult cancelJob(String jobId);

    RdosTaskStatus getJobStatus(String jobId) throws IOException;

	String getJobMaster();
	
	String getMessageByHttp(String path);

}
