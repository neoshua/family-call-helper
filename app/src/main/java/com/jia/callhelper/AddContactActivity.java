package com.jia.callhelper;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

/**
 * 添加家人：称呼（必填）+ 微信备注名（选填）+ 自动接听开关（默认关）。
 */
public class AddContactActivity extends Activity {

    private EditText mName;
    private EditText mWechat;
    private Switch mAuto;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_contact);

        mName = (EditText) findViewById(R.id.input_name);
        mWechat = (EditText) findViewById(R.id.input_wechat);
        mAuto = (Switch) findViewById(R.id.sw_auto);
        mAuto.setChecked(false); // 默认关，避免误接隐私通话

        final Button btnSave = (Button) findViewById(R.id.btn_save);
        // 称呼为空时禁用保存按钮，老人无法提交空联系人；输入后自动恢复可用
        btnSave.setEnabled(!mName.getText().toString().trim().isEmpty());
        mName.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                btnSave.setEnabled(!s.toString().trim().isEmpty());
            }
        });

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        ((Button) findViewById(R.id.btn_cancel)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    private void save() {
        String name = mName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "请填写称呼，例如：大儿子", Toast.LENGTH_SHORT).show();
            return;
        }
        WhiteListManager.add(this, name, mWechat.getText().toString().trim(), mAuto.isChecked());
        Toast.makeText(this, "已添加：" + name, Toast.LENGTH_SHORT).show();
        finish(); // 回到首页（首页 onResume 会自动刷新列表）
    }
}
