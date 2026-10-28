package com.ethershare;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Invisible. EtherShare on the PC opens this through adb when it finds the Thor; the Connect
 * button uses it too. Asks for the VPN permission the first time, then starts the tunnel.
 */
public class ConnectActivity extends Activity {
    static final String EXTRA_MANUAL = "manual";
    private static final int ASK_VPN = 1;
    private String pc;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pc = getIntent().getStringExtra(TunnelService.EXTRA_PC);
        boolean manual = getIntent().getBooleanExtra(EXTRA_MANUAL, false);
        Prefs prefs = new Prefs(this);
        if (!manual && !prefs.autoConnect()) {
            finish();
            return;
        }
        if (!manual && prefs.openOnConnect()) LauncherActivity.open(this);
        Intent ask = VpnService.prepare(this);
        if (ask == null) {
            TunnelService.start(this, pc);
            finish();
            return;
        }
        try {
            startActivityForResult(ask, ASK_VPN);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Android couldn't show the VPN prompt: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == ASK_VPN) {
            if (resultCode == RESULT_OK) {
                TunnelService.start(this, pc);
            } else {
                Toast.makeText(this, "EtherShare needs the VPN permission to send the Thor's traffic through the cable",
                        Toast.LENGTH_LONG).show();
            }
        }
        finish();
    }
}
