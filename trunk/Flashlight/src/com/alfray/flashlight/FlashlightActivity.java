package com.alfray.flashlight;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;

public class FlashlightActivity extends Activity {

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        // Make the screen full bright for this activity using the new
        // cupcake brightness API.
        //
        // Reference:
        //   ./development/apps/Development/src/com/android/development/PointerLocation.java
        // or
        //   http://android.git.kernel.org/?p=platform/development.git;a=blob;f=apps/Development/src/com/android/development/PointerLocation.java;h=668e9ba167f590c97481e348ced5f97d45f307c9;hb=HEAD

        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1.0f;
        getWindow().setAttributes(lp);
    }

    @Override
    protected void onPause() {
        super.onPause();
    }
}