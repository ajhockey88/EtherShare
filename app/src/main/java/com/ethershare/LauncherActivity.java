package com.ethershare;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Display;

/**
 * Invisible trampoline. With both screens in use: the status and controls on the bottom
 * screen and the live speed graph on the top screen. Otherwise everything on one screen.
 */
public class LauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        open(this);
        finish();
    }

    static void open(Context context) {
        Display bottom = Displays.mainDisplay(context);
        Display top = Displays.graphDisplay(context);
        boolean dual = new Prefs(context).bothScreens() && bottom != null && top != null
                && bottom.getDisplayId() != top.getDisplayId();

        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        boolean placed = false;
        if (dual) {
            try {
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(bottom.getDisplayId());
                context.startActivity(intent, options.toBundle());
                placed = true;
            } catch (RuntimeException e) {
                // The system refused to place us on that screen; use one screen instead.
            }
        }
        if (!placed) {
            try {
                context.startActivity(intent);
            } catch (RuntimeException ignored) {
            }
        }
        if (placed) MainActivity.openGraphScreen(context);
    }
}
