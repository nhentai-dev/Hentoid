package me.devsaki.hentoid.fragments.about;

import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import me.devsaki.hentoid.R;

import static android.support.v4.view.ViewCompat.requireViewById;

public class LicensesFragment extends Fragment {

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_licenses, container, false);

        WebView webView = requireViewById(rootView, R.id.licenses);
        webView.loadUrl("file:///android_asset/licenses.html");
        webView.setInitialScale(95);

        return rootView;
    }
}