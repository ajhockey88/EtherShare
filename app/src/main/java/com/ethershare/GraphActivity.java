package com.ethershare;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.WindowManager;

/** The top screen: the live speed graph, full size. */
public class GraphActivity extends Activity implements TunnelService.Listener {
    private static GraphActivity current;
    private GraphView graph;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;
        getWindow().getDecorView().setBackgroundColor(Color.BLACK);
        graph = new GraphView(this, true);
        setContentView(graph);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (new Prefs(this).keepScreenOn()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        TunnelService.addListener(this);
        graph.invalidate();
    }

    @Override
    protected void onStop() {
        TunnelService.removeListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    @Override
    public void onTunnelChanged() { graph.invalidate(); }

    /** Closes the graph screen along with the main screen. */
    static void close() {
        if (current != null) current.finishAndRemoveTask();
    }
}
