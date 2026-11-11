package n0001;

import java.util.Vector;

public class n0184 {
    private Vector n0001;

    public n0184() {
        this.n0001 = new Vector();
    }

    public n0184(String tag) {
        this.n0001 = new Vector();
        try {
            n0241.injectAutoQuestMenuItems(this, tag);
        } catch (Throwable ignored) {
        }
    }

    public final void n0001(Object obj) {
        this.n0001.addElement(obj);
    }

    public final boolean n0002(Object obj) {
        return this.n0001.contains(obj);
    }

    public final int n0003() {
        if (this.n0001 == null) {
            return 0;
        }
        return this.n0001.size();
    }

    public final Object n0001(int index) {
        if (index >= 0 && index < this.n0001.size()) {
            return this.n0001.elementAt(index);
        }
        return null;
    }

    public final void n0001(Object obj, int index) {
        if (index >= 0 && index < this.n0001.size()) {
            this.n0001.setElementAt(obj, index);
        }
    }

    public final int n0003(Object obj) {
        return this.n0001.indexOf(obj);
    }

    public final void n0002(int index) {
        if (index >= 0 && index < this.n0001.size()) {
            this.n0001.removeElementAt(index);
        }
    }

    public final void n0004(Object obj) {
        this.n0001.removeElement(obj);
    }

    public final void n0004() {
        this.n0001.removeAllElements();
    }

    public final void n0002(Object obj, int index) {
        this.n0001.insertElementAt(obj, index);
    }
}
