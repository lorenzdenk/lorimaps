package io.github.lorenzdenk.lorimaps;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import org.xmlpull.v1.XmlPullParser;

public class SettingsActivity extends AppCompatActivity {

    public static final String ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android";

    static Activity context;
    private MyPreferenceFragment preferenceFragment;
    private static SharedPreferences prefs;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(null);

        context = this;

        prefs = PreferenceManager.getDefaultSharedPreferences(context);

        // own layout with toolbar: Android 15+ draws apps edge-to-edge, so the system bars
        // are kept free here explicitly (otherwise the list scrolls under the status bar)
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFFFFFFF);
        androidx.appcompat.widget.Toolbar toolbar = new androidx.appcompat.widget.Toolbar(this);
        toolbar.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, R.color.colorPrimaryDark));
        toolbar.setTitleTextColor(0xFFFFFFFF);
        root.addView(toolbar, new android.widget.LinearLayout.LayoutParams(-1, -2));
        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        container.setId(R.id.settingsContainer);
        root.addView(container, new android.widget.LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars() | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            toolbar.setPadding(bars.left, bars.top, bars.right, 0);
            container.setPadding(bars.left, 0, bars.right, bars.bottom);
            return androidx.core.view.WindowInsetsCompat.CONSUMED;
        });
        androidx.core.view.WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightStatusBars(false);
        androidx.core.view.WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightNavigationBars(true);
        setSupportActionBar(toolbar);
        setTitle(R.string.title_settings);

        preferenceFragment = new MyPreferenceFragment(this);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settingsContainer, preferenceFragment)
                .commit();

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowHomeEnabled(true);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        if (toolbar.getNavigationIcon() != null)
            toolbar.getNavigationIcon().setTint(0xFFFFFFFF);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                saveAndClose();
                break;
        }
        return true;
    }

    @Override
    public boolean onSupportNavigateUp(){
        saveAndClose();
        return true;
    }

    private void saveAndClose() {
        Intent resultIntent = new Intent();
        setResult(Activity.RESULT_OK, resultIntent);
        finish();
    }


    public static class MyPreferenceFragment extends PreferenceFragmentCompat {


        private final SettingsActivity settingsActivity;

        static final String LICENSE_TEXT =
                "LoriMaps – Karten mit GPS-Standort\n\n" +
                "Entwicklung\n" +
                "Programmiert von Claude (KI-Assistent von Anthropic) im Auftrag und nach den Wünschen des Nutzers: " +
                "Kartenmenü, Standortanzeige, Bedienung, App-Symbol und die Kartendarstellung mit MapLibre.\n\n" +
                "Grundlage\n" +
                "Oberfläche und Bedienung stammen aus der Open-Source-App „Simple OSM Viewer“ (OSMv).\n" +
                "Copyright (c) 2020 applikationsprogramvara. MIT-Lizenz.\n\n" +
                "MIT-Lizenz\n" +
                "Hiermit wird jeder Person, die eine Kopie dieser Software erhält, kostenlos die Erlaubnis erteilt, " +
                "sie uneingeschränkt zu nutzen, zu kopieren, zu ändern, zusammenzuführen, zu veröffentlichen, zu verbreiten, " +
                "zu unterlizenzieren und/oder zu verkaufen, sofern der obige Urheberrechtshinweis und dieser Erlaubnishinweis " +
                "in allen Kopien enthalten sind. Die Software wird ohne jede Gewährleistung bereitgestellt.\n" +
                "(Massgeblich ist der englische Originaltext in der Datei LICENSE des Quellcodes.)\n\n" +
                "Verwendete Bibliotheken\n" +
                "• MapLibre Native – BSD-Lizenz (2 Klauseln)\n" +
                "• OkHttp – Apache-Lizenz 2.0\n" +
                "• AndroidX – Apache-Lizenz 2.0\n\n" +
                "Kartendaten und Kartenstil\n" +
                "• Kartendaten © OpenStreetMap-Mitwirkende, Open Database License (ODbL)\n" +
                "• Kartenstil der eigenen Karten: OpenTopoMap (CC-BY-SA)\n" +
                "• Höhenmodell: Copernicus DEM, © DLR e.V. 2010–2014 und © Airbus Defence and Space GmbH 2014–2018, " +
                "bereitgestellt im Rahmen von COPERNICUS durch die Europäische Union und die ESA\n" +
                "• Online-Karten: es gelten die Bedingungen des jeweiligen Anbieters";

        public MyPreferenceFragment(SettingsActivity settingsActivity) {
            this.settingsActivity = settingsActivity;
        }

        @Override
        public void onCreatePreferences(Bundle bundle, String s) {
            init();
        }

        public void init() {
            addPreferencesFromResource(R.xml.settings);

            String[] values2bind = new String[]{
                    "UnitsOfMeasure"
            };

            for(String value: values2bind)
                bindPreferenceSummaryToValue(findPreference(value));

            if (!BuildConfig.DEBUG) {
//                PreferenceScreen mainScreen = findPreference("mainScreen");
//                mainScreen.removePreference(findPreference(""));
            } else {
//                findPreference("").setOnPreferenceClickListener(preference -> {
//                });
            }



            findPreference("License").setOnPreferenceClickListener(preference -> {
                android.widget.TextView tv = new android.widget.TextView(getContext());
                int pad = (int) (20 * getResources().getDisplayMetrics().density);
                tv.setPadding(pad, pad / 2, pad, 0);
                tv.setTextColor(0xFF222222);
                tv.setTextSize(15);
                tv.setText(LICENSE_TEXT);
                android.widget.ScrollView sv = new android.widget.ScrollView(getContext());
                sv.addView(tv);
                new AlertDialog.Builder(getContext())
                        .setTitle("Lizenz und Entwicklung")
                        .setView(sv)
                        .setPositiveButton(R.string.btn_ok, null)
                        .show();
                return true;
            });

            String version;
            String libVersion;
            try {
                version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
                libVersion = "MapLibre Native " + org.maplibre.android.BuildConfig.MAPLIBRE_VERSION_STRING;
            } catch (PackageManager.NameNotFoundException e) {
                Log.e("MyApp", e.toString());
                version = "unknown";
                libVersion = "unknown";
            }

            Preference about_pref = findPreference("About");
            about_pref.setSummary(
                    "- " + settingsActivity.getString(R.string.app_name) + ": " + version + (BuildConfig.DEBUG ? " debug" : "") + "\n" +
                    "- " + settingsActivity.getString(R.string.stg_version_lib) + ": " + libVersion
            );

        }



    }

    private static void bindPreferenceSummaryToValue(Preference preference) {
        if (preference == null)
            return;

        // Set the listener to watch for value changes.
        preference.setOnPreferenceChangeListener(sBindPreferenceSummaryToValueListener);

        // Trigger the listener immediately with the preference's
        // current value.
        sBindPreferenceSummaryToValueListener.onPreferenceChange(preference,
                PreferenceManager
                        .getDefaultSharedPreferences(preference.getContext())
                        .getString(preference.getKey(), ""));
    }


    private static Preference.OnPreferenceChangeListener sBindPreferenceSummaryToValueListener = (preference, value) -> {
        String stringValue = value.toString();

        if (preference instanceof ListPreference) {
            // For list preferences, look up the correct display value in
            // the preference's 'entries' list.
            ListPreference listPreference = (ListPreference) preference;
            int index = listPreference.findIndexOfValue(stringValue);

            // Set the summary to reflect the new value.
            preference.setSummary(
                    index >= 0
                            ? listPreference.getEntries()[index]
                            : null);

        } else if (preference instanceof EditTextPreference) {
            EditTextPreference editTextPreference = (EditTextPreference) preference;

            boolean floatPreference = //false;
                    //(editTextPreference.getEditText().getInputType() & InputType.TYPE_NUMBER_FLAG_DECIMAL) > 0;
                    //android:inputType="numberDecimal"
                    getETAttribute(editTextPreference.getKey(), "inputType").equals("0x2002");
            // inputType - numberDecimal - 0x2002

            boolean integerPreference = //false;
                    //(editTextPreference.getEditText().getInputType() & InputType.TYPE_CLASS_NUMBER) > 0;
                    // android:numeric="integer"
                    getETAttribute(editTextPreference.getKey(), "numeric").equals("0x1");
            // numeric - integer - 0x1

            if (floatPreference || integerPreference) {

                float[] extremums = getExtremums(preference.getKey());
                float min = extremums[0];
                float max = extremums[1];
                if ((min != 0) && (max != 0) && (min < max)) {
                    boolean correction = false;

                    float newFloatValue = StrToFloat((String) value, 0);
                    if (newFloatValue < min) {
                        value = min;
                        correction = true;
                        Toast.makeText(context, R.string.toast_error_value_low, Toast.LENGTH_LONG).show();
                    } else if (max < newFloatValue) {
                        value = max;
                        correction = true;
                        Toast.makeText(context, R.string.toast_error_value_high, Toast.LENGTH_LONG).show();
                    }

                    if (correction) {
                        String newStrValue = floatPreference ? Float.toString((float) value) : Integer.toString(Math.round((float) value));
                        //preference.getEditor().putString(preference.getKey(), newStrValue).apply();
                        preference.getSharedPreferences().edit().putString(preference.getKey(), newStrValue).apply();
                        editTextPreference.setText(newStrValue);
                        preference.setSummary(newStrValue);
                        return false;
                    }
                }
            }

            preference.setSummary(stringValue);
        } else {
            // For all other preferences, set the summary to the value's
            // simple string1 representation.
            preference.setSummary(stringValue);
        }
        return true;
    };

    static float[] getExtremums(String requestedKey) {


        float [] result = new float[] {0, 0};

        try {
            XmlPullParser xpp = context.getResources().getXml(R.xml.settings);

            xpp.next();
            int eventType = xpp.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
//				if (eventType == XmlPullParser.START_DOCUMENT) {
//					stringBuffer.append("--- Start XML ---");
//				} else
                if (eventType == XmlPullParser.START_TAG) {
                    //stringBuffer.append("\nSTART_TAG: " + xpp.getName());
                    if (xpp.getName().equals("EditTextPreference")) {
                        String curKey = xpp.getAttributeValue("http://schemas.android.com/apk/res/android", "key");
                        if (curKey != null) {
                            if (curKey.equals(requestedKey)) {
                                result[0] = StrToFloat(xpp.getAttributeValue(null, "min"), 0);
                                result[1] = StrToFloat(xpp.getAttributeValue(null, "max"), 0);
                                break;
                            }
                        }
                    }
                }
//				else if (eventType == XmlPullParser.END_TAG) {
//					stringBuffer.append("\nEND_TAG: " + xpp.getName());
//				} else if (eventType == XmlPullParser.TEXT) {
//					stringBuffer.append("\nTEXT: " + xpp.getText());
//				}
                //xpp.getAttributeValue("http://schemas.android.com/apk/res/android", "key")

                eventType = xpp.next();
            }
            //stringBuffer.append("\n--- End XML ---");
        }
        catch (Throwable t) {
            //Toast.makeText(context, "Request failed: "+t.toString(), Toast.LENGTH_LONG).show();
        }


        return result;

    }

    private static String getETAttribute(String circleRadius, String inputType) {
        return getAttribute("EditTextPreference", circleRadius, ANDROID_NAMESPACE, inputType, R.xml.settings);
    }

    static String getAttribute(String tag, String key, String namespace, String attribute, int resource) {
        try {
            XmlPullParser xpp = context.getResources().getXml(resource);

            xpp.next();
            int eventType = xpp.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    if (xpp.getName().equals(tag)) {
                        String curKey = xpp.getAttributeValue(ANDROID_NAMESPACE, "key");
                        if (curKey != null) {
                            if (curKey.equals(key)) {
                                String result = xpp.getAttributeValue(namespace, attribute);
                                if (result != null)
                                    return result;
                                else return "";
                            }
                        }
                    }
                }
                eventType = xpp.next();
            }
        }
        catch (Throwable t) {
            //Toast.makeText(context, "Request failed: "+t.toString(), Toast.LENGTH_LONG).show();
        }

        return "";
    }

    public static float StrToFloat(String str1, float default_value) {
        if (str1 == null)
            return default_value;
        try {
            return Float.parseFloat(str1);
        } catch (NumberFormatException e) {
            Log.e("MyApp", "Error: " + e.getMessage());
            return default_value;
        }
    }

}