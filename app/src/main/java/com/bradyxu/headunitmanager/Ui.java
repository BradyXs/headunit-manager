package com.bradyxu.headunitmanager;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ScaleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

final class Ui {
    // Mirrors DiPlay's current CARD theme; all values are opaque for API 19.
    static final int BACK = 0xff12151b;
    static final int RAIL = 0xff242830;
    static final int RAIL_SELECTED = 0xff1b8feb;
    static final int SURFACE = 0xff31353d;
    static final int SURFACE_LIGHT = 0xff454b55;
    static final int BUTTON = 0xff393e47;
    static final int BORDER = 0xff4d525b;
    static final int TEXT = 0xfff8f9fc;
    static final int MUTED = 0xffbfc4cd;
    static final int ACCENT = 0xff2c9ff5;
    static final int GREEN = 0xff34c759;
    static final int AMBER = 0xffffc480;
    private static final int LINE = BORDER;
    private Ui() {}

    static int dp(Context c, int value) {
        return (int) (value * c.getResources().getDisplayMetrics().density + .5f);
    }

    static float screenWidthDp(Context context) {
        return context.getResources().getDisplayMetrics().widthPixels /
            context.getResources().getDisplayMetrics().density;
    }

    static float contentWidthDp(Context context) {
        float width = screenWidthDp(context);
        return expandedLayout(context) ? width - 250 : width - 40;
    }

    static boolean expandedLayout(Context context) {
        android.content.res.Configuration config = context.getResources().getConfiguration();
        float scale = Math.max(1f, config.fontScale);
        return config.screenWidthDp >= 840 * scale && config.screenHeightDp >= 400;
    }

    static GradientDrawable shape(Context c, int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radius));
        if (color == SURFACE || color == BUTTON) d.setStroke(dp(c, 1), BORDER);
        return d;
    }

    static android.graphics.drawable.Drawable touchBackground(Context c, int normal, int pressed, int radius) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, shape(c, pressed, radius));
        states.addState(new int[]{android.R.attr.state_focused}, shape(c, pressed, radius));
        states.addState(new int[]{}, shape(c, normal, radius));
        return states;
    }

    static LinearLayout column(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        return v;
    }

    static LinearLayout row(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.HORIZONTAL);
        return v;
    }

    static TextView text(Context c, String value, int size) {
        TextView v = new TextView(c);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(TEXT);
        v.setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 8));
        return v;
    }

    static TextView label(Context c, String value) {
        TextView v = text(c, value, 12);
        v.setTextColor(MUTED);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }

    static ImageView icon(Context context, int resource, int color) {
        ImageView icon = new ImageView(context);
        icon.setImageResource(resource);
        icon.setColorFilter(color);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return icon;
    }

    static void styleVolumeSlider(SeekBar slider) {
        Context context = slider.getContext();
        GradientDrawable track = new GradientDrawable();
        track.setColor(0xff69717b);
        track.setCornerRadius(dp(context, 8));
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{0xff66e0eb, 0xff2c97f5});
        fill.setCornerRadius(dp(context, 8));
        LayerDrawable layers = new LayerDrawable(new Drawable[]{track,
            new ScaleDrawable(fill, Gravity.LEFT, 1f, -1f)});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        int inset = dp(context, 14);
        layers.setLayerInset(0, 0, inset, 0, inset);
        layers.setLayerInset(1, 0, inset, 0, inset);
        slider.setMinimumHeight(dp(context, 44));
        slider.setProgressDrawable(layers);
        slider.setThumb(new ColorDrawable(Color.TRANSPARENT));
        slider.setThumbOffset(0);
    }

    static void buttonIcon(Button button, int resource) {
        Drawable icon = button.getContext().getResources().getDrawable(resource).mutate();
        icon.setColorFilter(TEXT, android.graphics.PorterDuff.Mode.SRC_IN);
        icon.setBounds(0, 0, dp(button.getContext(), 20), dp(button.getContext(), 20));
        button.setCompoundDrawables(icon, null, null, null);
        button.setCompoundDrawablePadding(dp(button.getContext(), 8));
    }

    static LinearLayout sectionHeader(Context context, String title, int iconResource) {
        LinearLayout row = row(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = icon(context, iconResource, ACCENT);
        row.addView(icon, new LinearLayout.LayoutParams(dp(context, 24), dp(context, 24)));
        TextView heading = text(context, title, 17);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setPadding(dp(context, 10), 0, 0, 0);
        row.addView(heading);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(context, 34));
        params.bottomMargin = dp(context, 8);
        row.setLayoutParams(params);
        return row;
    }

    static Button button(Context c, String label, View.OnClickListener click) {
        Button b = new Button(c);
        b.setText(label);
        b.setTextSize(16);
        b.setTextColor(TEXT);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setAllCaps(false);
        b.setMinHeight(dp(c, 52));
        b.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        b.setBackground(touchBackground(c, BUTTON, SURFACE_LIGHT, 18));
        b.setOnClickListener(click);
        return b;
    }

    static Button primaryButton(Context c, String label, View.OnClickListener click) {
        Button button = button(c, label, click);
        button.setTextColor(BACK);
        button.setBackground(touchBackground(c, ACCENT, RAIL_SELECTED, 18));
        return button;
    }

    static void selectedButton(Button button, boolean selected) {
        Context context = button.getContext();
        button.setTextColor(selected ? Color.WHITE : TEXT);
        button.setBackground(touchBackground(context,
            selected ? RAIL_SELECTED : BUTTON,
            selected ? ACCENT : SURFACE_LIGHT, 14));
    }

    static EditText input(Context context) {
        EditText input = new EditText(context);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setTextSize(15);
        input.setSingleLine(true);
        input.setMinHeight(dp(context, 48));
        input.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));
        GradientDrawable focused = shape(context, BUTTON, 12);
        focused.setStroke(dp(context, 2), ACCENT);
        GradientDrawable normal = shape(context, BUTTON, 12);
        normal.setStroke(dp(context, 1), BORDER);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, normal);
        input.setBackground(states);
        return input;
    }

    static LinearLayout switchRow(Context context, String title, String detail,
                                  FlatSwitch toggle, View.OnClickListener click) {
        LinearLayout row = row(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 64));
        row.setPadding(dp(context, 14), dp(context, 7), dp(context, 14), dp(context, 7));
        row.setBackground(touchBackground(context, SURFACE, BUTTON, 14));
        row.setFocusable(true);
        LinearLayout labels = column(context);
        labels.setPadding(0, 0, dp(context, 12), 0);
        TextView name = text(context, title, 15);
        name.setPadding(0, 0, 0, 0);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        labels.addView(name);
        if (detail != null && !detail.isEmpty()) {
            TextView hint = text(context, detail, 12);
            hint.setTextColor(MUTED);
            hint.setPadding(0, dp(context, 2), 0, 0);
            labels.addView(hint);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        toggle.setContentDescription(title);
        toggle.setFocusable(false);
        row.addView(toggle);
        row.setOnClickListener(click);
        toggle.setOnClickListener(click);
        return row;
    }

    static LinearLayout actionGrid(Context context, LinearLayout actions) {
        LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        int count = actions.getChildCount();
        View[] children = new View[count];
        for (int i = 0; i < count; i++) children[i] = actions.getChildAt(i);
        actions.removeAllViews();
        int columns = gridColumns(context);
        for (int i = 0; i < count; i++) addGridItem(grid, children[i], i, columns, 52);
        finishGrid(grid, count, columns);
        return grid;
    }

    static final class FlatSwitch extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean checked;
        FlatSwitch(Context context) {
            super(context);
            setMinimumWidth(dp(context, 52));
            setMinimumHeight(dp(context, 32));
            setClickable(true);
            setFocusable(true);
        }
        void setChecked(boolean value) { checked = value; invalidate(); }
        boolean isChecked() { return checked; }
        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(android.widget.Switch.class.getName());
            info.setCheckable(true);
            info.setChecked(checked);
        }
        @Override protected void onMeasure(int width, int height) {
            setMeasuredDimension(dp(getContext(), 52), dp(getContext(), 32));
        }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), inset = dp(getContext(), 2);
            paint.setColor(checked ? GREEN : 0xff71757e);
            canvas.drawRoundRect(new RectF(0, 0, w, h), h / 2, h / 2, paint);
            paint.setColor(Color.WHITE);
            float radius = h / 2 - inset;
            canvas.drawCircle(checked ? w - h / 2 : h / 2, h / 2, radius, paint);
        }
    }

    static Dialog progress(Context context, String message) {
        Dialog dialog = new Dialog(context);
        LinearLayout panel = row(context);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(context, 24), dp(context, 22), dp(context, 24), dp(context, 22));
        panel.setBackground(shape(context, SURFACE, 18));
        android.widget.ProgressBar spinner = new android.widget.ProgressBar(context);
        spinner.setIndeterminate(true);
        panel.addView(spinner, new LinearLayout.LayoutParams(dp(context, 32), dp(context, 32)));
        TextView title = text(context, message, 15);
        title.setPadding(dp(context, 16), 0, 0, 0);
        panel.addView(title);
        dialog.setContentView(panel);
        dialog.setCancelable(false);
        dialog.show();
        styleWindow(context, dialog);
        return dialog;
    }

    static final class TransferProgress {
        private final Dialog dialog;
        private final android.widget.ProgressBar bar;
        private final TextView amount;

        TransferProgress(Context context, String filename, DialogAction cancel) {
            dialog = new Dialog(context);
            dialog.setCancelable(false);
            LinearLayout panel = column(context);
            panel.setBackground(shape(context, SURFACE, 18));
            TextView heading = text(context, "正在下载文件", 18);
            heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            panel.addView(heading);
            TextView name = text(context, filename, 13);
            name.setTextColor(MUTED);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            panel.addView(name);
            bar = new android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
            bar.setIndeterminate(true);
            bar.setMax(100);
            LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(context, 8));
            progressParams.setMargins(dp(context, 8), dp(context, 12), dp(context, 8), 0);
            panel.addView(bar, progressParams);
            amount = text(context, "正在连接…", 13);
            amount.setTextColor(MUTED);
            panel.addView(amount);
            panel.addView(button(context, "取消下载", v -> {
                amount.setText("正在取消…");
                if (cancel != null) cancel.run();
            }));
            dialog.setContentView(panel);
            dialog.show();
            styleWindow(context, dialog);
        }

        void setProgress(int percent) {
            bar.setIndeterminate(false);
            bar.setProgress(percent);
            amount.setText(percent + "%");
        }

        void dismiss() { dialog.dismiss(); }
    }

    static AlertDialog.Builder dialog(Context context) { return new FlatDialogBuilder(context); }

    private static final class FlatDialogBuilder extends AlertDialog.Builder {
        FlatDialogBuilder(Context context) { super(context); }

        @Override public AlertDialog create() {
            AlertDialog dialog = super.create();
            dialog.setOnShowListener(ignored -> styleDialog(dialog));
            return dialog;
        }

        @Override public AlertDialog show() {
            AlertDialog dialog = create();
            dialog.show();
            return dialog;
        }
    }

    private static void styleDialog(AlertDialog dialog) {
        Context c = dialog.getContext();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = .55f;
            window.setAttributes(attributes);
            int width = (int) (c.getResources().getDisplayMetrics().widthPixels /
                c.getResources().getDisplayMetrics().density);
            window.setLayout(dp(c, Math.max(300, Math.min(560, width - 48))), -2);
        }
        int parentId = c.getResources().getIdentifier("parentPanel", "id", "android");
        View parent = parentId == 0 ? null : dialog.findViewById(parentId);
        if (parent != null) parent.setBackground(shape(c, SURFACE, 18));
        for (String name : new String[]{"topPanel", "contentPanel", "customPanel", "buttonPanel"}) {
            int id = c.getResources().getIdentifier(name, "id", "android");
            View section = id == 0 ? null : dialog.findViewById(id);
            if (section != null) section.setBackgroundColor(Color.TRANSPARENT);
        }
        int titleId = c.getResources().getIdentifier("alertTitle", "id", "android");
        View titleView = titleId == 0 ? null : dialog.findViewById(titleId);
        TextView title = titleView instanceof TextView ? (TextView) titleView : null;
        if (title != null) {
            title.setTextColor(TEXT);
            title.setTextSize(18);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setGravity(Gravity.CENTER);
            title.setPadding(dp(c, 20), dp(c, 18), dp(c, 20), dp(c, 12));
        }
        View messageView = dialog.findViewById(android.R.id.message);
        TextView message = messageView instanceof TextView ? (TextView) messageView : null;
        if (message != null) {
            message.setTextColor(TEXT);
            message.setTextSize(14);
            message.setGravity(Gravity.CENTER);
            message.setPadding(dp(c, 22), dp(c, 4), dp(c, 22), dp(c, 16));
            message.setLineSpacing(dp(c, 2), 1f);
        }
        View choicesView = dialog.findViewById(android.R.id.list);
        ListView choices = choicesView instanceof ListView ? (ListView) choicesView : null;
        if (choices != null) {
            choices.setBackgroundColor(Color.TRANSPARENT);
            choices.setDivider(new ColorDrawable(LINE));
            choices.setDividerHeight(dp(c, 1));
            choices.setSelector(touchBackground(c, Color.TRANSPARENT, BUTTON, 8));
            choices.setPadding(dp(c, 8), 0, dp(c, 8), 0);
            styleChoiceRows(c, choices);
            choices.post(() -> styleChoiceRows(c, choices));
        }
        int[] ids = {android.R.id.button1, android.R.id.button2, android.R.id.button3};
        for (int i = 0; i < ids.length; i++) {
            View buttonView = dialog.findViewById(ids[i]);
            if (!(buttonView instanceof Button)) continue;
            Button button = (Button) buttonView;
            button.setTextColor(i == 1 ? MUTED : TEXT);
            button.setTextSize(15);
            button.setAllCaps(false);
            button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            button.setMinHeight(dp(c, 50));
            button.setBackground(touchBackground(c, BUTTON, SURFACE_LIGHT, 12));
        }
    }

    private static void styleChoiceRows(Context context, ListView choices) {
        for (int i = 0; i < choices.getChildCount(); i++) {
            View row = choices.getChildAt(i);
            View labelView = row.findViewById(android.R.id.text1);
            TextView label = labelView instanceof TextView ? (TextView) labelView : null;
            if (label != null) {
                label.setTextColor(TEXT);
                label.setTextSize(15);
                label.setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));
                label.setMinHeight(dp(context, 48));
            }
            if (row instanceof CheckedTextView) {
                ((CheckedTextView) row).setCheckMarkDrawable(
                    new ChoiceMarkDrawable(context, choices.getChoiceMode() == ListView.CHOICE_MODE_MULTIPLE));
            }
        }
    }

    interface OptionListener { void selected(int index); }
    interface DialogAction { void run(); }

    static void optionsDialog(Context context, String title, String detail, String[] options,
                              int selectedIndex, OptionListener listener) {
        Dialog dialog = new Dialog(context);
        LinearLayout panel = flatPanel(context, title, detail);
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout rows = column(context);
        rows.setPadding(dp(context, 8), 0, dp(context, 8), 0);
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            LinearLayout row = row(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(context, 16), dp(context, 6), dp(context, 16), dp(context, 6));
            row.setMinimumHeight(dp(context, 52));
            row.setBackground(touchBackground(context, Color.TRANSPARENT, BUTTON, 8));
            TextView label = text(context, options[i], 15);
            label.setPadding(0, 0, dp(context, 10), 0);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            TextView mark = text(context, index == selectedIndex ? "✓" : "", 18);
            mark.setTextColor(ACCENT);
            mark.setGravity(Gravity.CENTER);
            mark.setPadding(0, 0, 0, 0);
            row.addView(mark, new LinearLayout.LayoutParams(dp(context, 26), dp(context, 26)));
            row.setOnClickListener(v -> {
                dialog.dismiss();
                listener.selected(index);
            });
            rows.addView(row, new LinearLayout.LayoutParams(-1, -2));
            if (i < options.length - 1) rows.addView(divider(context));
        }
        scroll.addView(rows);
        int contentHeight = dialogBodyHeight(context, options.length, 53);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, contentHeight));
        addDialogFooter(context, panel, dialog, null, "关闭", null);
        dialog.setContentView(panel);
        dialog.show();
        styleWindow(context, dialog);
    }

    static void multiChoiceDialog(Context context, String title, String detail, String[] options,
                                  boolean[] checked, String cancelText, String saveText, DialogAction onSave) {
        Dialog dialog = new Dialog(context);
        LinearLayout panel = flatPanel(context, title, detail);
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout rows = column(context);
        rows.setPadding(dp(context, 8), 0, dp(context, 8), 0);
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            LinearLayout row = row(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(context, 16), dp(context, 6), dp(context, 16), dp(context, 6));
            row.setMinimumHeight(dp(context, 54));
            row.setBackground(touchBackground(context, Color.TRANSPARENT, BUTTON, 8));
            TextView label = text(context, options[i], 14);
            label.setPadding(0, 0, dp(context, 12), 0);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            TextView mark = choiceMark(context, checked[i]);
            row.addView(mark, new LinearLayout.LayoutParams(dp(context, 24), dp(context, 24)));
            row.setOnClickListener(v -> {
                checked[index] = !checked[index];
                updateChoiceMark(context, mark, checked[index]);
            });
            rows.addView(row, new LinearLayout.LayoutParams(-1, -2));
            if (i < options.length - 1) rows.addView(divider(context));
        }
        scroll.addView(rows);
        int contentHeight = dialogBodyHeight(context, options.length, 55);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, contentHeight));
        addDialogFooter(context, panel, dialog, cancelText, saveText, onSave);
        dialog.setContentView(panel);
        dialog.show();
        styleWindow(context, dialog);
    }

    private static LinearLayout flatPanel(Context context, String title, String detail) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(shape(context, SURFACE, 18));
        panel.setPadding(0, dp(context, 8), 0, 0);
        TextView heading = text(context, title, 18);
        heading.setTextColor(TEXT);
        heading.setGravity(Gravity.CENTER);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setPadding(dp(context, 22), dp(context, 12), dp(context, 22), dp(context, 4));
        panel.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        if (detail != null && !detail.trim().isEmpty()) {
            TextView subtitle = text(context, detail, 13);
            subtitle.setTextColor(MUTED);
            subtitle.setGravity(Gravity.CENTER);
            subtitle.setLineSpacing(dp(context, 2), 1f);
            subtitle.setPadding(dp(context, 22), dp(context, 2), dp(context, 22), dp(context, 14));
            panel.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));
        } else {
            View spacer = new View(context);
            panel.addView(spacer, new LinearLayout.LayoutParams(-1, dp(context, 12)));
        }
        panel.addView(divider(context));
        return panel;
    }

    private static void addDialogFooter(Context context, LinearLayout panel, Dialog dialog,
                                        String cancelText, String saveText, DialogAction onSave) {
        panel.addView(divider(context));
        LinearLayout actions = row(context);
        if (cancelText == null) {
            TextView close = footerButton(context, saveText, false);
            actions.addView(close, new LinearLayout.LayoutParams(-1, dp(context, 50)));
            close.setOnClickListener(v -> dialog.dismiss());
        } else {
            TextView cancel = footerButton(context, cancelText, true);
            TextView save = footerButton(context, saveText, false);
            actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(context, 50), 1));
            actions.addView(save, new LinearLayout.LayoutParams(0, dp(context, 50), 1));
            cancel.setOnClickListener(v -> dialog.dismiss());
            save.setOnClickListener(v -> {
                dialog.dismiss();
                if (onSave != null) onSave.run();
            });
        }
        panel.addView(actions, new LinearLayout.LayoutParams(-1, dp(context, 50)));
    }

    private static TextView footerButton(Context context, String label, boolean quiet) {
        TextView button = text(context, label, 15);
        button.setTextColor(quiet ? MUTED : TEXT);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setBackground(touchBackground(context, BUTTON, SURFACE_LIGHT, 12));
        return button;
    }

    private static TextView choiceMark(Context context, boolean checked) {
        TextView mark = text(context, "", 15);
        mark.setGravity(Gravity.CENTER);
        mark.setPadding(0, 0, 0, 0);
        updateChoiceMark(context, mark, checked);
        return mark;
    }

    private static void updateChoiceMark(Context context, TextView mark, boolean checked) {
        mark.setText(checked ? "✓" : "");
        mark.setTextColor(checked ? Color.WHITE : MUTED);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(context, 6));
        shape.setColor(checked ? GREEN : SURFACE);
        if (!checked) shape.setStroke(dp(context, 1), BORDER);
        mark.setBackground(shape);
    }

    private static View divider(Context context) {
        View line = new View(context);
        line.setBackgroundColor(LINE);
        line.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(context, 1)));
        return line;
    }

    private static void styleWindow(Context context, Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.dimAmount = .55f;
        window.setAttributes(attributes);
        int width = (int) (context.getResources().getDisplayMetrics().widthPixels /
            context.getResources().getDisplayMetrics().density);
        window.setLayout(dp(context, Math.max(300, Math.min(560, width - 48))), -2);
    }

    private static int dialogBodyHeight(Context context, int rows, int rowHeight) {
        float density = context.getResources().getDisplayMetrics().density;
        int screenHeight = (int) (context.getResources().getDisplayMetrics().heightPixels / density);
        int maximum = Math.max(120, Math.min(360, screenHeight - 160));
        return Math.min(dp(context, maximum), dp(context, rows * rowHeight));
    }

    private static final class ChoiceMarkDrawable extends Drawable {
        private final Context context;
        private final boolean multiple;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean checked;

        ChoiceMarkDrawable(Context context, boolean multiple) {
            this.context = context;
            this.multiple = multiple;
        }

        @Override public boolean isStateful() { return true; }

        @Override protected boolean onStateChange(int[] state) {
            boolean next = false;
            for (int value : state) if (value == android.R.attr.state_checked) next = true;
            if (checked == next) return false;
            checked = next;
            invalidateSelf();
            return true;
        }

        @Override public void draw(Canvas canvas) {
            RectF bounds = new RectF(getBounds());
            float side = Math.min(bounds.width(), bounds.height()) - dp(context, 4);
            float left = bounds.centerX() - side / 2f;
            float top = bounds.centerY() - side / 2f;
            paint.setStyle(checked ? Paint.Style.FILL : Paint.Style.STROKE);
            paint.setStrokeWidth(dp(context, 2));
            paint.setColor(checked ? (multiple ? GREEN : ACCENT) : MUTED);
            if (multiple) {
                RectF box = new RectF(left + dp(context, 2), top + dp(context, 2),
                    left + side - dp(context, 2), top + side - dp(context, 2));
                canvas.drawRoundRect(box, dp(context, 4), dp(context, 4), paint);
                if (checked) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(context, 2));
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    paint.setStrokeJoin(Paint.Join.ROUND);
                    paint.setColor(Color.WHITE);
                    Path tick = new Path();
                    tick.moveTo(box.left + dp(context, 4), box.centerY());
                    tick.lineTo(box.left + dp(context, 7), box.centerY() + dp(context, 3));
                    tick.lineTo(box.right - dp(context, 4), box.top + dp(context, 5));
                    canvas.drawPath(tick, paint);
                }
            } else {
                float radius = side / 2f - dp(context, 2);
                canvas.drawCircle(bounds.centerX(), bounds.centerY(), radius, paint);
                if (checked) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(Color.WHITE);
                    canvas.drawCircle(bounds.centerX(), bounds.centerY(), radius * .38f, paint);
                }
            }
        }

        @Override public int getIntrinsicWidth() { return dp(context, 24); }
        @Override public int getIntrinsicHeight() { return dp(context, 24); }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    static TextView badge(Context c, String value, int background, int foreground) {
        TextView v = text(c, value, 11);
        v.setTextColor(foreground);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3));
        v.setBackground(shape(c, background, 8));
        return v;
    }

    static LinearLayout card(Context c) {
        LinearLayout card = column(c);
        card.setPadding(dp(c, 20), dp(c, 16), dp(c, 20), dp(c, 16));
        card.setBackground(shape(c, SURFACE, 20));
        return card;
    }

    static LinearLayout listItem(Context c, String title, String subtitle, String mark, View.OnClickListener click) {
        LinearLayout item = row(c);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
        item.setMinimumHeight(dp(c, 68));
        item.setBackground(touchBackground(c, SURFACE, SURFACE_LIGHT, 12));
        item.setFocusable(true);
        TextView badge = text(c, mark, 16);
        badge.setTextSize(mark.length() > 1 ? 10 : 18);
        badge.setGravity(Gravity.CENTER);
        badge.setTextColor(ACCENT);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setBackground(shape(c, BUTTON, 10));
        item.addView(badge, new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)));
        LinearLayout words = column(c);
        words.setPadding(dp(c, 12), 0, dp(c, 8), 0);
        TextView name = text(c, title, 16);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(0, 0, 0, 0);
        words.addView(name);
        TextView detail = text(c, subtitle, 12);
        detail.setTextColor(MUTED);
        detail.setSingleLine(true);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        detail.setPadding(0, dp(c, 2), 0, 0);
        words.addView(detail);
        item.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = text(c, "›", 24);
        arrow.setTextColor(MUTED);
        item.addView(arrow);
        item.setOnClickListener(click);
        return item;
    }

    static int gridColumns(Context c) {
        float width = contentWidthDp(c);
        return width >= 720 ? 3 : width >= 480 ? 2 : 1;
    }

    static void addGridItem(LinearLayout grid, View item, int index, int columns) {
        addGridItem(grid, item, index, columns, 76);
    }

    static void addGridItem(LinearLayout grid, View item, int index, int columns, int heightDp) {
        Context c = grid.getContext();
        LinearLayout line;
        if (index % columns == 0) {
            line = row(c);
            grid.addView(line, new LinearLayout.LayoutParams(-1, -2));
        } else line = (LinearLayout) grid.getChildAt(grid.getChildCount() - 1);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(c, heightDp), 1);
        params.setMargins(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
        line.addView(item, params);
    }

    static void finishGrid(LinearLayout grid, int count, int columns) {
        if (count == 0 || count % columns == 0) return;
        LinearLayout line = (LinearLayout) grid.getChildAt(grid.getChildCount() - 1);
        for (int i = count % columns; i < columns; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1);
            p.setMargins(dp(grid.getContext(), 4), 0, dp(grid.getContext(), 4), 0);
            line.addView(new View(grid.getContext()), p);
        }
    }

    static LinearLayout iconItem(Context c, String title, String detail, android.graphics.drawable.Drawable icon, View.OnClickListener click) {
        LinearLayout item = listItem(c, title, detail, "", click);
        item.removeViewAt(0);
        android.widget.ImageView image = new android.widget.ImageView(c);
        image.setImageDrawable(icon);
        image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        item.addView(image, 0, new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)));
        return item;
    }

    static android.graphics.drawable.Drawable fileIcon(boolean folder, boolean apk) {
        return new android.graphics.drawable.Drawable() {
            final android.graphics.Paint paint = new android.graphics.Paint(3);
            @Override public void draw(android.graphics.Canvas canvas) {
                android.graphics.Rect b = getBounds();
                canvas.save();
                canvas.translate(b.left, b.top);
                canvas.scale(b.width() / 48f, b.height() / 48f);
                paint.setStyle(android.graphics.Paint.Style.FILL);
                paint.setColor(BUTTON);
                canvas.drawRoundRect(new android.graphics.RectF(1, 1, 47, 47), 10, 10, paint);
                paint.setColor(ACCENT);
                if (folder) {
                    paint.setStyle(android.graphics.Paint.Style.STROKE);
                    paint.setStrokeWidth(2.5f);
                    paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
                    android.graphics.Path path = new android.graphics.Path();
                    path.moveTo(10, 18); path.lineTo(10, 16);
                    path.quadTo(10, 13, 13, 13); path.lineTo(20, 13);
                    path.lineTo(24, 18); path.lineTo(35, 18);
                    path.quadTo(38, 18, 38, 21); path.lineTo(38, 34);
                    path.quadTo(38, 37, 35, 37); path.lineTo(13, 37);
                    path.quadTo(10, 37, 10, 34); path.close();
                    canvas.drawPath(path, paint);
                } else {
                    paint.setStyle(android.graphics.Paint.Style.STROKE);
                    paint.setStrokeWidth(2.3f);
                    android.graphics.Path paper = new android.graphics.Path();
                    paper.moveTo(15, 10); paper.lineTo(28, 10); paper.lineTo(35, 17);
                    paper.lineTo(35, 37); paper.lineTo(15, 37); paper.close();
                    canvas.drawPath(paper, paint);
                    canvas.drawLine(28, 10, 28, 17, paint);
                    canvas.drawLine(28, 17, 35, 17, paint);
                    if (apk) {
                        paint.setStyle(android.graphics.Paint.Style.FILL);
                        paint.setTextAlign(android.graphics.Paint.Align.CENTER);
                        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                        paint.setTextSize(9);
                        canvas.drawText("APK", 25, 29, paint);
                    } else {
                        canvas.drawLine(19, 25, 31, 25, paint);
                        canvas.drawLine(19, 30, 29, 30, paint);
                    }
                }
                canvas.restore();
            }
            @Override public int getIntrinsicWidth() { return 48; }
            @Override public int getIntrinsicHeight() { return 48; }
            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    static LinearLayout.LayoutParams rowSpacing(Context c) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(c, 10);
        return p;
    }

    @SuppressWarnings("deprecation")
    static void toast(Context c, String value) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            Toast.makeText(c, value, Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout card = row(c);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(c, 14), dp(c, 9), dp(c, 18), dp(c, 9));
        card.setBackground(shape(c, SURFACE, 14));
        TextView marker = badge(c, "•", BUTTON, ACCENT);
        marker.setTextSize(16);
        card.addView(marker, new LinearLayout.LayoutParams(dp(c, 26), dp(c, 26)));
        TextView message = text(c, value, 14);
        message.setTextColor(TEXT);
        message.setPadding(dp(c, 10), 0, 0, 0);
        message.setMaxLines(3);
        float density = c.getResources().getDisplayMetrics().density;
        int screenWidth = (int) (c.getResources().getDisplayMetrics().widthPixels / density);
        message.setMaxWidth(dp(c, Math.max(220, Math.min(620, screenWidth - 90))));
        card.addView(message, new LinearLayout.LayoutParams(-2, -2));
        Toast toast = new Toast(c);
        toast.setView(card);
        toast.setDuration(Toast.LENGTH_LONG);
        toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dp(c, 28));
        toast.show();
    }

    static void confirm(Context c, String title, String message, Runnable yes) {
        dialog(c).setTitle(title).setMessage(message)
            .setNegativeButton("取消", null).setPositiveButton("继续", (d, w) -> yes.run()).show();
    }

    static ScrollView scroll(Context c, View content) {
        ScrollView s = new ScrollView(c);
        s.addView(content);
        s.setFillViewport(true);
        return s;
    }

    static HorizontalScrollView horizontalScroll(Context c, View content) {
        HorizontalScrollView s = new HorizontalScrollView(c);
        s.addView(content);
        return s;
    }
}
