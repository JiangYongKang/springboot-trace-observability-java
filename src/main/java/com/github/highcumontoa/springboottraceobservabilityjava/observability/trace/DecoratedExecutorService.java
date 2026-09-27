package com.github.highcumontoa.springboottraceobservabilityjava.observability.trace;

import org.springframework.core.task.TaskDecorator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 用 {@link TracingTaskDecorator} 装饰所有提交任务的 {@link ExecutorService} 包装。
 * execute/submit/invokeAll/invokeAny 入口统一过 {@link TaskDecorator}，
 * 保证线程池切换线程时链路上下文与 MDC 正确传播、执行后还原。
 */
public class DecoratedExecutorService implements ExecutorService {

    private final ExecutorService delegate;
    private final TaskDecorator decorator;

    public DecoratedExecutorService(ExecutorService delegate, TaskDecorator decorator) {
        this.delegate = delegate;
        this.decorator = decorator;
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(decorator.decorate(command));
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(decorator.decorate(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(decorator.decorate(task), result);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(decorateCallable(task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(decorateCallables(tasks));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        return delegate.invokeAll(decorateCallables(tasks), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, java.util.concurrent.ExecutionException {
        return delegate.invokeAny(decorateCallables(tasks));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
        return delegate.invokeAny(decorateCallables(tasks), timeout, unit);
    }

    private <T> Callable<T> decorateCallable(Callable<T> task) {
        // 复用 Runnable 装饰逻辑：在调用方线程捕获上下文快照，于执行线程恢复后调用业务 Callable
        return () -> {
            Object[] resultHolder = new Object[1];
            Throwable[] errorHolder = new Throwable[1];
            decorator.decorate((Runnable) () -> {
                try {
                    resultHolder[0] = task.call();
                } catch (Throwable t) {
                    errorHolder[0] = t;
                }
            }).run();
            if (errorHolder[0] != null) {
                Throwable t = errorHolder[0];
                if (t instanceof RuntimeException re) {
                    throw re;
                }
                if (t instanceof Error err) {
                    throw err;
                }
                throw (Exception) t;
            }
            @SuppressWarnings("unchecked")
            T result = (T) resultHolder[0];
            return result;
        };
    }

    private <T> Collection<Callable<T>> decorateCallables(Collection<? extends Callable<T>> tasks) {
        List<Callable<T>> list = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            list.add(decorateCallable(task));
        }
        return list;
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
