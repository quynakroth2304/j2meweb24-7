package javax.microedition.midlet;
import javax.microedition.lcdui.Display;
import java.util.HashMap;

public abstract class MIDlet {
    public static HashMap<String, String> properties = new HashMap<String, String>();
    private Display display;

    protected MIDlet() {
        this.display = new Display();
        System.out.println("Create MIDlet");
    }

    public final int checkPermission(String permission) { return -1; }
    protected abstract void destroyApp(boolean unconditional) throws MIDletStateChangeException;
    public String getAppProperty(String key) { return properties != null ? properties.get(key) : null; }
    public static void initAppProperties(HashMap<String, String> props) { properties = props; }

    public final void notifyDestroyed() {
        System.out.println("[VM-CLOUD] MIDlet goi notifyDestroyed() -> Bo qua de giu may ao 24/7 luon chay!");
    }

    public final void notifyPaused() {}
    protected abstract void pauseApp();

    public final boolean platformRequest(String url) {
        System.out.println("OPEN_URL:" + url);
        return false;
    }

    public final void resumeRequest() {}
    protected abstract void startApp() throws MIDletStateChangeException;
    public Display getDisplay() { return this.display; }
}
