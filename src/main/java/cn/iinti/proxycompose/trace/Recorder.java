package cn.iinti.proxycompose.trace;



import cn.iinti.proxycompose.loop.Looper;
import cn.iinti.proxycompose.trace.impl.SubscribeRecorders;
import cn.iinti.proxycompose.trace.utils.StringSplitter;
import cn.iinti.proxycompose.trace.utils.ThrowablePrinter;

import java.util.Collection;
import java.util.LinkedList;
import java.util.function.Function;

public abstract class Recorder {
    protected static final Looper thread = new Looper("eventRecorder").startLoop();

    public static Looper workThread() {
        return thread;
    }


    public void recordEvent(String message) {
        recordEvent(() -> message, (Throwable) null);
    }

    public void recordEvent(String message, Throwable throwable) {
        recordEvent(() -> message, throwable);
    }

    public void recordEvent(MessageGetter messageGetter) {
        recordEvent(messageGetter, (Throwable) null);
    }

    public <T> void recordEvent(T t, Function<T, String> fuc) {
        recordEvent(() -> fuc.apply(t));
    }

    public abstract void recordEvent(MessageGetter messageGetter, Throwable throwable);

    public void recordMosaicMsgIfSubscribeRecorder(MessageGetter message) {
        if (this instanceof SubscribeRecorders.SubscribeRecorder) {
            SubscribeRecorders.SubscribeRecorder s = (SubscribeRecorders.SubscribeRecorder) this;
            s.recordMosaicMsg(message);
        } else {
            recordEvent(message);
        }
    }

    protected Collection<String> splitMsg(String msg, Throwable throwable) {
        Collection<String> strings = StringSplitter.split(msg, '\n');
        if (throwable == null) {
            return strings;
        }
        if (strings.isEmpty()) {
            // 确保可以被编辑
            strings = new LinkedList<>();
        }
        ThrowablePrinter.printStackTrace(strings, throwable);
        return strings;
    }

    public interface MessageGetter {
        String getMessage();
    }

    public static final Recorder nop = new Recorder() {
        @Override
        public void recordEvent(MessageGetter messageGetter, Throwable throwable) {

        }
    };
}
