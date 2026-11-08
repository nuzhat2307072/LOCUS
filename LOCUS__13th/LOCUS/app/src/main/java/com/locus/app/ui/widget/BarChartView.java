package com.locus.app.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.locus.app.R;

/** Stacked bar chart: one bar per period, one colour per travel mode. Drawn by hand (no chart library). */
public class BarChartView extends View {
    /** walk, cycle, car */
    public static final int[] MODE_COLORS = {0xFF2E7D32, 0xFF1976D2, 0xFFEF6C00};

    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axis = new Paint();
    private double[][] values = new double[3][0];
    private String[] labels = new String[0];
    private String unit = "";

    public BarChartView(Context c) {
        super(c);
        init();
    }

    public BarChartView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        float d = getResources().getDisplayMetrics().density;
        text.setTextSize(11 * d);
        text.setTextAlign(Paint.Align.CENTER);
        axis.setStrokeWidth(d);
    }

    /** @param values [mode][bucket]; every row has labels.length entries */
    public void setData(double[][] values, String[] labels, String unit) {
        this.values = values;
        this.labels = labels;
        this.unit = unit;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        int n = labels.length;
        int secondary = ContextCompat.getColor(getContext(), R.color.locus_text_secondary);
        int primary = ContextCompat.getColor(getContext(), R.color.locus_text);
        axis.setColor(secondary);
        if (n == 0) return;
        float top = 18 * d, bottom = getHeight() - 22 * d, left = 8 * d, right = getWidth() - 8 * d;
        double max = 0;
        double[] totals = new double[n];
        for (int i = 0; i < n; i++) {
            for (double[] v : values) totals[i] += v[i];
            max = Math.max(max, totals[i]);
        }
        if (max <= 0) max = 1;
        float slot = (right - left) / n;
        float w = slot * 0.6f;
        canvas.drawLine(left, bottom, right, bottom, axis);
        for (int i = 0; i < n; i++) {
            float cx = left + slot * (i + 0.5f);
            float y = bottom;
            for (int m = 0; m < values.length; m++) {
                float h = (float) (values[m][i] / max * (bottom - top));
                if (h <= 0) continue;
                bar.setColor(MODE_COLORS[m]);
                canvas.drawRect(cx - w / 2, y - h, cx + w / 2, y, bar);
                y -= h;
            }
            if (totals[i] > 0) {
                text.setColor(primary);
                canvas.drawText(format(totals[i]), cx, y - 4 * d, text);
            }
            text.setColor(secondary);
            canvas.drawText(labels[i], cx, getHeight() - 6 * d, text);
        }
    }

    private String format(double v) {
        return (v >= 100 ? String.valueOf(Math.round(v)) : String.format(java.util.Locale.getDefault(), "%.1f", v)) + unit;
    }
}
