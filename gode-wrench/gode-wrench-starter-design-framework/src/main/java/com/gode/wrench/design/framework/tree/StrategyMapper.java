package com.gode.wrench.design.framework.tree;

public interface StrategyMapper<T, D extends DynamicContext, R> {

    StrategyHandler<T, D, R> get(T requestParameter, D dynamicContext) throws Exception;

}
