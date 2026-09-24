package com.jia.callhelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

/**
 * 家人详情 / 编辑 / 删除。
 * 删除采用两次确认弹窗，防止老人误触把常用联系人删掉。
 */
public class ContactDetailActivity extends Activity {

    private String mKey;
    private WhiteListManager.Entry mEntry;
    private EditText mName;
    private EditText mWechat;
    private Switch mAuto;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mKey = getIntent().getStringExtra("key");
        mEntry = WhiteListManager.get(this, mKey);
        if (mEntry == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_contact_detail);

        mName = (EditText) findViewById(R.id.input_name);
        mWechat = (EditText) findViewById(R.id.input_wechat);
        mAuto = (Switch) findViewById(R.id.sw_auto);
        mName.setText(mEntry.name);
        mWechat.setText(mEntry.number);
        mAuto.setChecked(mEntry.auto);

        ((Button) findViewById(R.id.btn_save)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        ((Button) findViewById(R.id.btn_delete)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete1();
            }
        });
        ((Button) findViewById(R.id.btn_back)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    private void save() {
        String name = mName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "称呼不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        WhiteListManager.update(this, mKey, name, mWechat.getText().toString().trim(), mAuto.isChecked());
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    /** 第一次确认 */
    private void confirmDelete1() {
        new AlertDialog.Builder(this)
                .setTitle("删除家人")
                .setMessage("确定要删除「" + mEntry.name + "」吗？删除后不再为其自动接听来电。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        confirmDelete2();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 第二次确认（防误触） */
    private void confirmDelete2() {
        new AlertDialog.Builder(this)
                .setTitle("再确认一次")
                .setMessage("真的要删除「" + mEntry.name + "」吗？此操作无法撤销。")
                .setPositiveButton("确定删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        WhiteListManager.remove(ContactDetailActivity.this, mKey);
                        Toast.makeText(ContactDetailActivity.this, "已删除：" + mEntry.name, Toast.LENGTH_SHORT).show();
                        finish();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
