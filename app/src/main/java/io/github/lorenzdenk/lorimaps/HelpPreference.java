package io.github.lorenzdenk.lorimaps;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

/** Text-only entry for the help section: shows the whole summary (the default cuts it after 10 lines). */
public class HelpPreference extends Preference {

    public HelpPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        TextView title = (TextView) holder.findViewById(android.R.id.title);
        TextView summary = (TextView) holder.findViewById(android.R.id.summary);
        if (title != null) {
            title.setSingleLine(false);
            title.setMaxLines(3);
        }
        if (summary != null) {
            summary.setMaxLines(Integer.MAX_VALUE);
            summary.setTextColor(0xFF444444);
        }
    }
}
