package com.gode.wrench.design.framework.tree;

import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public abstract class AbstractMultiThreadStrategyRouter<T, D extends DynamicContext, R> implements StrategyHandler<T, D, R>, StrategyMapper<T, D, R> {

    @Getter
    @Setter
    protected StrategyHandler<T, D, R> defaultStrategyHandler = StrategyHandler.DEFAULT;

    public R router(T requestParameter, D dynamicContext) throws Exception {
        StrategyHandler<T, D, R> strategyHandler = get(requestParameter, dynamicContext);
        if(null != strategyHandler) return strategyHandler.apply(requestParameter, dynamicContext);
        return defaultStrategyHandler.apply(requestParameter, dynamicContext);
    }


    @Override
    public R apply(T requestParameter, D dynamicContext) throws Exception {
        try {
            // 前置处理
            R applyBefore = applyBefore(requestParameter, dynamicContext);
            if(null != applyBefore) return applyBefore;

            // 异步加载数据
            multiThread(requestParameter, dynamicContext);

            // 业务流程受理
            R apply = doApply(requestParameter, dynamicContext);

            // 后置处理
            applyAfter(requestParameter, dynamicContext, apply);

            return apply;

        } catch (Exception e) {
            // 后置处理（异常）
            applyAfterException(requestParameter, dynamicContext, e);
            throw e;
        }
    }


    /**
     * 异步加载数据
     */
    protected void multiThread(T requestParameter, D dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {

    }

    protected abstract R doApply(T requestParameter, D dynamicContext) throws Exception;

    protected R applyBefore(T requestParameter, D dynamicContext) {
        return proceed(requestParameter, dynamicContext);
    }

    protected void applyAfter(T requestParameter, D dynamicContext, R r) {

    }

    protected void applyAfterException(T requestParameter, D dynamicContext, Exception e) {

    }

}
