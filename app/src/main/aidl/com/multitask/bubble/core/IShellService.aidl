package com.multitask.bubble.core;

interface IShellService {
    void destroy() = 16777114; // required by Shizuku
    String exec(String command) = 1;
    boolean injectTouch(int displayId, int action, long downTime, in int[] ids, in float[] xs, in float[] ys) = 2;
    boolean injectKey(int displayId, int keyCode) = 3;
}
