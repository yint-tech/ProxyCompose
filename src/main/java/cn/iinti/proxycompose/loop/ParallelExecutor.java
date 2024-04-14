package cn.iinti.proxycompose.loop;

public class ParallelExecutor<T> implements ValueCallback<T> {
    private final Looper looper;

    private final int eventSize;
    private int eventIndex = 0;
    private boolean success = false;
    private final ParallelConnectEvent<T> parallelConnectEvent;

    public ParallelExecutor(Looper looper, int eventSize, ParallelConnectEvent<T> parallelConnectEvent) {
        this.looper = looper;
        this.eventSize = eventSize;
        this.parallelConnectEvent = parallelConnectEvent;
    }

    @Override
    public void onReceiveValue(Value<T> value) {
        looper.execute(() -> {
            eventIndex++;

            if (value.isSuccess()) {
                if (!success) {
                    success = true;
                    parallelConnectEvent.firstSuccess(value);
                } else {
                    parallelConnectEvent.secondSuccess(value);
                }
                return;
            }

            if (!success && eventIndex >= eventSize) {
                parallelConnectEvent.finalFailed(value.e);
            }
        });


    }

    //private List<>

    public interface ParallelConnectEvent<T> {
        void firstSuccess(Value<T> value);

        void secondSuccess(Value<T> value);

        void finalFailed(Throwable throwable);
    }
}
