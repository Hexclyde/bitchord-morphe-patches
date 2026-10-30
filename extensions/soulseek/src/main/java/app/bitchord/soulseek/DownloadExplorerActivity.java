package app.bitchord.soulseek;

import android.app.Activity;
import android.os.Bundle;

/** Standalone fallback for hosts without the 1.9 embedded player route. */
public final class DownloadExplorerActivity extends Activity {
    private DownloadPage page;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        page = new DownloadExplorerPage(this, getIntent());
        page.onCreate(state);
        setContentView(page.view);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (page != null) page.onStart();
    }

    @Override
    protected void onStop() {
        if (page != null) page.onStop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (page != null) page.onDestroy();
        super.onDestroy();
    }
}
