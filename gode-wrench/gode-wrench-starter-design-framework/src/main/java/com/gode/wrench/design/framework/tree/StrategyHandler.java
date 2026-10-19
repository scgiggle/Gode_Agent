package com.gode.wrench.design.framework.tree;

/**
 * 相当于是一个节点
 * @param <T>
 * @param <D>
 * @param <R>
 */

public interface StrategyHandler<T, D extends DynamicContext, R> {

    StrategyHandler DEFAULT = (T, D) -> null;

    default R proceed(T requestParameter, D dynamicContext) {
        return null;
    }

    R apply(T requestParameter, D dynamicContext) throws Exception;

}
