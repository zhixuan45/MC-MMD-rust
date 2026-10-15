package com.shiroha.mmdskin.render.material;

/** Three bounded, lazy caches: full world mesh, first-person mesh, and GUI mesh. */
public final class ViewTranslucentOrders implements AutoCloseable {
    private final TranslucentDrawOrder[] orders = new TranslucentDrawOrder[3];

    public TranslucentDrawOrder get(boolean gui, boolean firstPerson) {
        int slot = gui ? 2 : firstPerson ? 1 : 0;
        if (orders[slot] == null) orders[slot] = new TranslucentDrawOrder();
        return orders[slot];
    }

    public long ramBytes() {
        long total = 0;
        for (var order : orders) if (order != null) total += order.ramBytes();
        return total;
    }

    public long vramBytes() {
        long total = 0;
        for (var order : orders) if (order != null) total += order.vramBytes();
        return total;
    }

    @Override public void close() {
        for (int i = 0; i < orders.length; i++) {
            if (orders[i] != null) orders[i].close();
            orders[i] = null;
        }
    }
}
