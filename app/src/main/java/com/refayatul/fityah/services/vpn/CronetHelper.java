package com.refayatul.fityah.services.vpn;

import androidx.annotation.Nullable;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;
import org.chromium.net.CronetException;

/**
 * Bridge class to bypass Kotlin's strict null checks for Cronet callbacks.
 * Cronet sometimes passes null UrlResponseInfo even if the signature doesn't say so.
 */
public abstract class CronetHelper extends UrlRequest.Callback {
    @Override
    public void onFailed(UrlRequest request, @Nullable UrlResponseInfo info, CronetException error) {
        onFailedSafe(request, info, error);
    }

    @Override
    public void onCanceled(UrlRequest request, @Nullable UrlResponseInfo info) {
        onCanceledSafe(request, info);
    }

    public abstract void onFailedSafe(@Nullable UrlRequest request, @Nullable UrlResponseInfo info, @Nullable CronetException error);
    public abstract void onCanceledSafe(@Nullable UrlRequest request, @Nullable UrlResponseInfo info);
}
