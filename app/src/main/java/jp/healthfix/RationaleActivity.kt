package jp.healthfix
import android.app.Activity
import android.os.Bundle
import android.widget.TextView
class RationaleActivity:Activity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContentView(TextView(this).apply{text="health-fix は、あなたが指定した歩数を Health Connect に保存し、アプリ内の履歴と照合するため、歩数の読み書き権限を使用します。データは端末内に保存され、外部へ送信しません。";textSize=18f;setPadding(32,48,32,32)})}}
