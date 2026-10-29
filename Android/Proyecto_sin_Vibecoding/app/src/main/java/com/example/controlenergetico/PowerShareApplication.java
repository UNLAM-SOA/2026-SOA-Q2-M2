package com.example.controlenergetico;

import android.app.Application;

public final class PowerShareApplication extends Application {
    private PowerShareRepository repository;
    @Override public void onCreate() {
        super.onCreate();
        repository = new PowerShareRepository(this);
    }
    public PowerShareRepository repository() { return repository; }
}
