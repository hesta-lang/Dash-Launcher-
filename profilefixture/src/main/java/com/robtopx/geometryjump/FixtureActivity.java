package com.robtopx.geometryjump;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Small CI-only stand-in for the original game's launcher activity. */
public final class FixtureActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView message = new TextView(this);
        message.setText("Dash Launcher profile isolation fixture");
        message.setTextSize(20f);
        setContentView(message);
    }
}
