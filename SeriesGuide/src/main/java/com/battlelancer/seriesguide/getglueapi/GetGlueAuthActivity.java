/*
 * Copyright 2011 Uwe Trottmann
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package com.battlelancer.seriesguide.getglueapi;

import android.content.Context;
import android.content.res.Resources;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.format.DateUtils;
import android.util.Log;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.actionbarsherlock.view.Window;
import com.battlelancer.seriesguide.settings.GetGlueSettings;
import com.battlelancer.seriesguide.ui.BaseNavDrawerActivity;
import com.battlelancer.seriesguide.util.Utils;
import com.uwetrottmann.seriesguide.R;

import org.apache.oltu.oauth2.client.OAuthClient;
import org.apache.oltu.oauth2.client.URLConnectionClient;
import org.apache.oltu.oauth2.client.request.OAuthClientRequest;
import org.apache.oltu.oauth2.client.response.OAuthJSONAccessTokenResponse;
import org.apache.oltu.oauth2.common.exception.OAuthProblemException;
import org.apache.oltu.oauth2.common.exception.OAuthSystemException;

import oauth.signpost.OAuthConsumer;
import oauth.signpost.OAuthProvider;

/**
 * Starts an OAuth 2.0 authentication flow via an {@link android.webkit.WebView}.
 */
public class GetGlueAuthActivity extends BaseNavDrawerActivity {

    final String TAG = "GetGlueAuthActivity";

    private OAuthConsumer mConsumer;

    private OAuthProvider mProvider;

    private WebView mWebview;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // webview uses a progress bar
        requestWindowFeature(Window.FEATURE_PROGRESS);

        super.onCreate(savedInstanceState);

        mWebview = new WebView(this);
        getMenu().setContentView(mWebview);

        final ActionBar actionBar = getSupportActionBar();
        actionBar.setTitle(getString(R.string.oauthmessage));
        actionBar.setDisplayShowTitleEnabled(true);

        setSupportProgressBarVisibility(true);

        final SherlockFragmentActivity activity = this;
        mWebview.setWebChromeClient(new WebChromeClient() {
            public void onProgressChanged(WebView view, int progress) {
                /*
                 * Activities and WebViews measure progress with different
                 * scales. The progress meter will automatically disappear when
                 * we reach 100%.
                 */
                activity.setSupportProgress(progress * 1000);
            }
        });
        mWebview.setWebViewClient(new WebViewClient() {
            public void onReceivedError(WebView view, int errorCode, String description,
                                        String failingUrl) {
                Toast.makeText(activity,
                        getString(R.string.getglue_authfailed) + " " + description,
                        Toast.LENGTH_LONG).show();

                finish();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith(GetGlueCheckin.OAUTH_CALLBACK_URL)) {
                    Uri uri = Uri.parse(url);

                    new RetrieveAccessTokenTask(getApplicationContext()).execute(uri);

                    finish();
                    return true;
                }
                return false;
            }
        });

        // mWebview.getSettings().setJavaScriptEnabled(true);

        Log.d(TAG, "Initiating authorization request...");
        Resources res = getResources();
        try {
            OAuthClientRequest request = com.uwetrottmann.getglue.GetGlue
                    .getAuthorizationRequest(res.getString(R.string.getglue_client_id),
                            GetGlueCheckin.OAUTH_CALLBACK_URL);
            mWebview.loadUrl(request.getLocationUri());
        } catch (OAuthSystemException e) {
            Utils.trackExceptionAndLog(TAG, e);
        }
    }

    public class RetrieveAccessTokenTask extends AsyncTask<Uri, Void, Integer> {

        private static final int AUTH_FAILED = 0;

        private static final int AUTH_SUCCESS = 1;

        private Context mContext;

        public RetrieveAccessTokenTask(Context context) {
            mContext = context;
        }

        /**
         * Retrieve the oauth_verifier, and store the oauth and
         * oauth_token_secret for future API calls.
         */
        @Override
        protected Integer doInBackground(Uri... params) {
            final Uri uri = params[0];
            final String authCode = uri.getQueryParameter("code");

            try {
                Log.d(TAG, "Building token request...");

                Resources resources = mContext.getResources();
                OAuthClientRequest request = com.uwetrottmann.getglue.GetGlue.getAccessTokenRequest(
                        resources.getString(R.string.getglue_client_id),
                        resources.getString(R.string.getglue_client_secret),
                        GetGlueCheckin.OAUTH_CALLBACK_URL,
                        authCode
                );

                //create OAuth client that uses custom http client under the hood
                OAuthClient client = new OAuthClient(new URLConnectionClient());
                OAuthJSONAccessTokenResponse response = client.accessToken(request);

                // store access and refresh token, as well as expiration date of access token
                long expiresIn = response.getExpiresIn();
                long expirationDate = System.currentTimeMillis() + expiresIn * DateUtils.SECOND_IN_MILLIS;
                PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                        .putString(GetGlueSettings.KEY_AUTH_TOKEN, response.getAccessToken())
                        .putLong(GetGlueSettings.KEY_AUTH_EXPIRATION, expirationDate)
                        .putString(GetGlueSettings.KEY_REFRESH_TOKEN, response.getRefreshToken())
                        .commit();

                return AUTH_SUCCESS;
            } catch (OAuthSystemException e) {
                Utils.trackExceptionAndLog(TAG, e);
            } catch (OAuthProblemException e) {
                Utils.trackExceptionAndLog(TAG, e);
            }

            return AUTH_FAILED;
        }

        @Override
        protected void onPostExecute(Integer result) {
            switch (result) {
                case AUTH_SUCCESS:
                    break;
                case AUTH_FAILED:
                    Toast.makeText(getApplicationContext(), getString(R.string.getglue_authfailed),
                            Toast.LENGTH_LONG).show();
                    break;
            }
        }
    }

}
