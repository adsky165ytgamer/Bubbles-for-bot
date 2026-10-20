package com.multitask.bubble.core;

interface IShellService {
    void destroy() = 16777114; // required by Shizuku
    String exec(String command) = 1;
}
