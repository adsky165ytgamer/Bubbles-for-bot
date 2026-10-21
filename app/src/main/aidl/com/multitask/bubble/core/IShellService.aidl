package com.multitask.bubble.core;

interface IShellService {
    void destroy() = 16777114; // required by Shizuku
    String exec(String command) = 1;
    boolean injectMotion(int displayId, int action, float x, float y, long downTime) = 2;
    boolean injectKey(int displayId, int keyCode) = 3;
}

