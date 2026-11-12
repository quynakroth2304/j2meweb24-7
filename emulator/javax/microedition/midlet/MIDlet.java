package javax.microedition.midlet;

import java.util.HashMap;
import javax.microedition.lcdui.Display;

public abstract class MIDlet {
    public static HashMap<String, String> properties;
    private Display display;

    protected MIDlet() {
        this.display = new Display();
        System.out.println("Create MIDlet");
    }

    public final int checkPermission(String permission) {
        System.out.println("checkPermission: " + permission);
        return -1;
    }

    protected abstract void destroyApp(boolean unconditional) throws MIDletStateChangeException;

    public String getAppProperty(String key) {
        return properties != null ? properties.get(key) : null;
    }

    public static void initAppProperties(HashMap<String, String> initProperties) {
        properties = initProperties;
    }

    public final void notifyDestroyed() {
        System.err.println("[MIDlet] Đã chặn lệnh notifyDestroyed() để giữ cửa sổ Game PC / Máy ảo 24/7 không bị tự tắt.");
    }

    public final void notifyPaused() {
    }

    protected abstract void pauseApp();

    public final boolean platformRequest(String URL) {
        return false;
    }

    public final void resumeRequest() {
    }

    protected abstract void startApp() throws MIDletStateChangeException;

    public Display getDisplay() {
        return this.display;
    }
}
