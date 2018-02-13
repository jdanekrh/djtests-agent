import com.redhat.mqe.ClientListener;

public interface StringListener extends ClientListener{
    void onMessage(String string);
}
