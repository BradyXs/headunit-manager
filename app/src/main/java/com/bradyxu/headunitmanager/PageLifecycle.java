package com.bradyxu.headunitmanager;

interface PageLifecycle {
    void onHostResume();
    default void onHostPause() { }
}
