package app.bitchord.soulseek;

import android.app.Activity;
import android.os.Bundle;

public final class BatchDownloadActivity extends Activity {
    private DownloadPage page;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        page = new BatchDownloadPage(this, getIntent());
        page.onCreate(state);
        setContentView(page.view);
    }

    @Override
    protected void onStart() {
        super.onStart();
        page.onStart();
    }

    @Override
    protected void onStop() {
        page.onStop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        page.onDestroy();
        super.onDestroy();
    }
}
