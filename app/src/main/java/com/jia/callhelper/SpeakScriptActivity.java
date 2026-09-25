package com.jia.callhelper;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 播报文案自定义。
 *
 * 用户要求："在 app 里面再添加一个功能，所有的语音播报都可以自定义"。
 *
 * 界面上每一条文案一行：标题 + 说明 + 输入框 + 「试听」「恢复默认」。
 * 输入框留空 = 恢复默认文案，所以用户永远不会把播报弄成无声。
 * 保存时立即生效（下一次来电就用新文案），不需要重启 App。
 */
public class SpeakScriptActivity extends Activity {

    /** 试听时用的示例称呼，让用户听得出效果 */
    private static final String SAMPLE_NAME = "大儿子";

    private final java.util.List<EditText> mEdits = new java.util.ArrayList<EditText>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(30));

        TextView title = new TextView(this);
        title.setText("播报文字设置");
        title.setTextSize(28);
        title.setTextColor(0xFF222222);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("下面每一句都是来电时手机会念出来的话，你可以随便改成老人听得懂的叫法。\n"
                + "留空就恢复成默认。\n\n" + SpeakScript.placeholderHelp());
        help.setTextSize(16);
        help.setTextColor(0xFF666666);
        help.setLineSpacing(dp(4), 1f);
        help.setPadding(0, dp(10), 0, dp(18));
        root.addView(help);

        for (final SpeakScript.Item item : SpeakScript.Item.values()) {
            root.addView(buildRow(item));
        }

        Button resetAll = new Button(this);
        resetAll.setText("全部恢复默认");
        resetAll.setTextSize(18);
        resetAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SpeakScript.resetAll(SpeakScriptActivity.this);
                for (int i = 0; i < mEdits.size(); i++) {
                    mEdits.get(i).setText(SpeakScript.Item.values()[i].def);
                }
                toast("已全部恢复默认");
            }
        });
        root.addView(resetAll);

        Button back = new Button(this);
        back.setText("返回");
        back.setTextSize(18);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        root.addView(back);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    /** 单条文案的编辑行 */
    private View buildRow(final SpeakScript.Item item) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(14), 0, dp(6));

        TextView t = new TextView(this);
        t.setText(item.title);
        t.setTextSize(19);
        t.setTextColor(0xFF222222);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(t);

        TextView h = new TextView(this);
        h.setText(item.hint);
        h.setTextSize(14);
        h.setTextColor(0xFF999999);
        box.addView(h);

        final EditText ed = new EditText(this);
        ed.setText(SpeakScript.get(this, item));
        ed.setTextSize(18);
        ed.setMinLines(2);
        ed.setGravity(android.view.Gravity.TOP);
        ed.setPadding(dp(10), dp(10), dp(10), dp(10));
        mEdits.add(ed);

        // 输入即保存：老人不会记得点"保存"，所以做成实时生效
        ed.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                String v = s.toString().trim();
                // 与默认值相同就删掉自定义（保持"没改过"的状态）
                if (v.isEmpty() || v.equals(item.def)) {
                    SpeakScript.reset(SpeakScriptActivity.this, item);
                } else {
                    SpeakScript.set(SpeakScriptActivity.this, item, v);
                }
            }
        });
        box.addView(ed);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        Button listen = new Button(this);
        listen.setText("▶ 试听");
        listen.setTextSize(16);
        listen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String txt = ed.getText().toString().trim();
                if (txt.isEmpty()) txt = item.def;
                // 用真实引擎念，占位符换成示例值，让用户听到实际效果
                TtsSpeaker.init(SpeakScriptActivity.this);
                TtsSpeaker.speak(SpeakScript.fill(txt, SAMPLE_NAME, true, 8));
            }
        });
        btns.addView(listen);

        Button def = new Button(this);
        def.setText("恢复默认");
        def.setTextSize(16);
        def.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SpeakScript.reset(SpeakScriptActivity.this, item);
                ed.setText(item.def);
                toast("已恢复默认文案");
            }
        });
        btns.addView(def);

        box.addView(btns);
        return box;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
