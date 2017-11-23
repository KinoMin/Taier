package com.dtstack.rdos.engine.execution.base.components;

import com.dtstack.rdos.commom.exception.RdosException;
import com.dtstack.rdos.common.util.MathUtil;
import com.dtstack.rdos.engine.execution.base.JobClient;
import com.dtstack.rdos.engine.execution.base.JobSubmitExecutor;
import com.dtstack.rdos.engine.execution.base.enumeration.EDeployType;
import com.dtstack.rdos.engine.execution.base.enumeration.EngineType;
import com.dtstack.rdos.engine.execution.base.util.SparkStandaloneRestParseUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 
 * @author sishu.yss
 *
 */
public class SlotsJudge {

    private static final Logger logger = LoggerFactory.getLogger(SlotsJudge.class);

    public static final String FLINK_SQL_ENV_PARALLELISM = "sql.env.parallelism";

    public static final String FLINK_MR_PARALLELISM = "mr.job.parallelism";

    public static final String SPARK_EXE_MEM = "executor.memory";

    public static final String SPARK_DRIVER_MEM = "driver.memory";

    public static final String SPARK_DRIVER_CPU = "driver.cores";

    public static final String STANDALONE_SPARK_EXECUTOR_CORES = "executor.cores";

    public static final String STANDALONE_SPARK_MAX_CORES = "cores.max";

    public static final int DEFAULT_EXECUTOR_CORES = 1;

    public static final int DEFAULT_CORES_MAX = 1;

    public static final Pattern capacityPattern = Pattern.compile("(\\d+)\\s*([a-zA-Z]{1,2})");

    /**
	 * 判断job所依赖的执行引擎的资源是否足够
	 * @param jobClient
	 * @param slotsInfo
	 * @return
	 */
	public boolean judgeSlots(JobClient jobClient, Map<String, Map<String,Map<String,Object>>> slotsInfo){

        EDeployType deployType = JobSubmitExecutor.getInstance().getEngineDeployType(jobClient.getEngineType());

		if(EngineType.isFlink(jobClient.getEngineType()) && deployType == EDeployType.STANDALONE){
			String flinkKey = null;
			for(String key : slotsInfo.keySet()){
				if(EngineType.isFlink(key)){
					flinkKey = key;
					break;
				}
			}

			if(flinkKey == null){
				throw new RdosException("not support engine type:" + jobClient.getEngineType());
			}

			return judgeFlinkResource(jobClient, slotsInfo.get(flinkKey));
		}else if(EngineType.isSpark(jobClient.getEngineType()) && deployType == EDeployType.STANDALONE){

			String sparkKey = null;
			for(String key : slotsInfo.keySet()){
				if(EngineType.isSpark(key)){
					sparkKey = key;
					break;
				}
			}

			if(sparkKey == null){
				throw new RdosException("not support engine type:" + jobClient.getEngineType());
			}

			return judgeSparkResource(jobClient, slotsInfo.get(sparkKey));
		}else{
			logger.info("not support engine type:{} and return default true.", jobClient.getEngineType());
			return true;
		}
	}

	/**
	 * 必须为各个taskManager
     * FIXME 当前只对在属性中设置了parallelism的任务进行控制
	 * @param jobClient
	 * @param slotsInfo
	 * @return
	 */
	public boolean judgeFlinkResource(JobClient jobClient, Map<String, Map<String,Object>> slotsInfo){

        int availableSlots = 0;
        for(Map<String, Object> value : slotsInfo.values()){
            int freeSlots = MathUtil.getIntegerVal(value.get("freeSlots"));
            availableSlots += freeSlots;
        }

        boolean result = true;
        if(jobClient.getConfProperties().containsKey(FLINK_SQL_ENV_PARALLELISM)){
            int maxParall = MathUtil.getIntegerVal(jobClient.getConfProperties().get(FLINK_SQL_ENV_PARALLELISM));
            result = result && availableSlots >= maxParall;
        }

        if(jobClient.getConfProperties().containsKey(FLINK_MR_PARALLELISM)){
            int maxParall = MathUtil.getIntegerVal(jobClient.getConfProperties().get(FLINK_MR_PARALLELISM));
            result = result && availableSlots >= maxParall;
        }

        return result;
	}

	/**
	 * 为各个worker 预留1024M的剩余空间
	 * @param jobClient
	 * @param slotsInfo
	 * @return
	 */
	public boolean judgeSparkResource(JobClient jobClient, Map<String, Map<String,Object>> slotsInfo){

	    int coreNum = 0;
	    int memNum = 0;
	    for(Map<String, Object> tmpMap : slotsInfo.values()){
            int workerFreeMem = (int) tmpMap.get(SparkStandaloneRestParseUtil.MEMORY_FREE_KEY);
            int workerFreeCpu = (int) tmpMap.get(SparkStandaloneRestParseUtil.CORE_FREE_KEY);
            memNum += workerFreeMem;
            coreNum += workerFreeCpu;
        }

        Properties properties = jobClient.getConfProperties();
        int coresMax = properties.containsKey(STANDALONE_SPARK_MAX_CORES) ?
                (int) properties.get(STANDALONE_SPARK_MAX_CORES) : DEFAULT_CORES_MAX;

        int executorCores = properties.contains(STANDALONE_SPARK_EXECUTOR_CORES) ?
                (int) properties.get(STANDALONE_SPARK_EXECUTOR_CORES) : DEFAULT_EXECUTOR_CORES;

        int executorNum = coresMax/executorCores;
        executorNum = executorNum > 0 ? executorNum : 1;

        return checkNeedMEMForSpark(jobClient, memNum, executorNum) && checkNeedCPUForSpark(jobClient, coreNum, executorNum);
	}

    /**
     * 判断
     * @param jobClient
     * @param memNum
     * @return
     */
	public boolean checkNeedMEMForSpark(JobClient jobClient, int memNum, int executorNum){
        int needMem = 0;
        if(jobClient.getConfProperties().containsKey(SPARK_DRIVER_MEM)) {
            String driverMem = (String) jobClient.getConfProperties().get(SPARK_DRIVER_MEM);
            needMem += convert2MB(driverMem);
        }else{//默认driver内存512
            needMem += 512;
        }

        int executorMem = 0;
        if(jobClient.getConfProperties().containsKey(SPARK_EXE_MEM)){
            String exeMem = (String) jobClient.getConfProperties().get(SPARK_EXE_MEM);
            executorMem = convert2MB(exeMem);
        }else{//默认app内存512M
            executorMem = 512;
        }

        executorMem = executorMem * executorNum;
        needMem += executorMem;

        if(needMem > memNum){
            return false;
        }

        return true;
    }

    /**
     * 判断core是否符合需求
     * @param jobClient
     * @param coreNum
     * @return
     */
	public boolean checkNeedCPUForSpark(JobClient jobClient, int coreNum, int executorNum){
        int needCore = 0;
        if(jobClient.getConfProperties().containsKey(SPARK_DRIVER_CPU)){
            String driverCPU = (String) jobClient.getConfProperties().get(SPARK_DRIVER_CPU);
            needCore += MathUtil.getIntegerVal(driverCPU);
        }else{
            needCore += 1;
        }

        int executorCores = 0;
        if(jobClient.getConfProperties().containsKey(STANDALONE_SPARK_MAX_CORES)){
            String exeCPU = (String) jobClient.getConfProperties().get(STANDALONE_SPARK_MAX_CORES);
            executorCores = MathUtil.getIntegerVal(exeCPU);
        }else{
            executorCores = 1;
        }

        needCore += executorCores;

        if(needCore > coreNum){
            return false;
        }
        return true;
    }

    /**
     * 暂时只做kb,mb,gb转换
     * @param memStr
     * @return
     */
	public Integer convert2MB(String memStr){
        Matcher matcher = capacityPattern.matcher(memStr);
        if(matcher.find() && matcher.groupCount() == 2){
            String num = matcher.group(1);
            String unit = matcher.group(2).toLowerCase();
            if(unit.contains("g")){
                Double mbNum = MathUtil.getDoubleVal(num) * 1024;
                return mbNum.intValue();
            }else if(unit.contains("m")){
                return MathUtil.getDoubleVal(num).intValue();
            }else if(unit.contains("k")){
                Double mbNum = MathUtil.getDoubleVal(num) / 1024;
                return mbNum.intValue();
            }else{
                 logger.error("can not convert memStr:" + memStr +", return default 512.");
            }
        }else{
            logger.error("can not convert memStr:" + memStr +", return default 512.");
        }

        return 512;
    }


	
}
