package com.jia.callhelper;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 自定义来电话术编辑页。
 *
 * <p>微信的来电邀请话术默认是中文（见 {@link NotifyPhrasesPrefs#DEFAULT_INVITE}），
 * 但微信换文案、或老人用的是海外版/英文版时，默认词表匹配不上 → 来电不触发。
 * 这个页把词表开放给用户编辑：每行一句，保存即生效；「恢复默认」回到内置词表。
 */
public class NotifyPhrasesActivity extends Activity {

    private EditText mInvite;
    private EditText mNotInvite;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notify_phrases);
        CallDiag.init(this);

        mInvite = (EditText) findViewById(R.id.et_invite);
        mNotInvite = (EditText) findViewById(R.id.et_not_invite);

        // 编辑框里显示「当前生效」的词（无论是否自定义过，都方便直接改）
        mInvite.setText(join(NotifyPhrasesPrefs.getInvitePhrases(this)));
        mNotInvite.setText(join(NotifyPhrasesPrefs.getNotInvitePhrases(this)));

        ((Button) findViewById(R.id.btn_phrases_save)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        ((Button) findViewById(R.id.btn_phrases_reset)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                reset();
            }
        });
    }

    private static String join(Set<String> set) {
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(s);
        }
        return sb.toString();
    }

    private static Set<String> split(EditText et) {
        Set<String> set = new LinkedHashSet<>();
        String raw = et.getText() == null ? "" : et.getText().toString();
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) set.add(t);
        }
        return set;
    }

    private void save() {
        Set<String> invite = split(mInvite);
        Set<String> not = split(mNotInvite);
        NotifyPhrasesPrefs.setInvitePhrases(this, invite);
        NotifyPhrasesPrefs.setNotInvitePhrases(this, not);
        CallDiag.log("通知", "来电话术已自定义保存：邀请词 " + invite.size()
                + " 条，非邀请词 " + not.size() + " 条");
        Toast.makeText(this, "已保存（" + invite.size() + " 条邀请词）", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void reset() {
        NotifyPhrasesPrefs.resetToDefault(this);
        mInvite.setText(join(NotifyPhrasesPrefs.getInvitePhrases(this)));
        mNotInvite.setText(join(NotifyPhrasesPrefs.getNotInvitePhrases(this)));
        CallDiag.log("通知", "来电话术已恢复为默认值");
        Toast.makeText(this, "已恢复默认", Toast.LENGTH_SHORT).show();
    }
}
