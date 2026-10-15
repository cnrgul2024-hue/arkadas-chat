package com.arkadas.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

data class U(val uid: String, val name: String)
data class M(val from: String, val text: String)

val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

@Composable
fun App() {
    var me by remember { mutableStateOf(auth.currentUser?.let { U(it.uid, it.email.orEmpty().substringBefore("@")) }) }
    var chat by remember { mutableStateOf<U?>(null) }
    val m = me
    val c = chat
    when {
        m == null -> Login { me = it }
        c == null -> Home(m, { chat = it }) { auth.signOut(); me = null }
        else -> Chat(m, c) { chat = null }
    }
}

@Composable
fun Login(onOk: (U) -> Unit) {
    var n by remember { mutableStateOf("") }
    var p by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    fun go(reg: Boolean) {
        val name = n.trim().lowercase()
        if (!Regex("[a-z0-9_]{3,20}").matches(name)) { err = "Kullanıcı adı: 3-20 harf/rakam/_ olmalı"; return }
        if (p.length < 6) { err = "Şifre en az 6 karakter olmalı"; return }
        val mail = "$name@arkadas.app"
        val t = if (reg) auth.createUserWithEmailAndPassword(mail, p) else auth.signInWithEmailAndPassword(mail, p)
        t.addOnSuccessListener { r ->
            val u = r.user!!
            if (reg) db.collection("users").document(u.uid).set(mapOf("username" to name))
            onOk(U(u.uid, name))
        }.addOnFailureListener {
            err = (if (reg) "Kayıt olmadı (kullanıcı adı alınmış olabilir): " else "Giriş olmadı: ") + it.message
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center) {
        Text("Arkadaş Chat", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(n, { n = it }, label = { Text("Kullanıcı adı") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(p, { p = it }, label = { Text("Şifre") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
        Row(Modifier.padding(top = 12.dp)) {
            Button({ go(false) }) { Text("Giriş") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton({ go(true) }) { Text("Kayıt ol") }
        }
    }
}

@Composable
fun Home(me: U, open: (U) -> Unit, out: () -> Unit) {
    var friends by remember { mutableStateOf(listOf<U>()) }
    var q by remember { mutableStateOf("") }
    var res by remember { mutableStateOf(listOf<U>()) }
    DisposableEffect(me.uid) {
        val l = db.collection("users").document(me.uid).collection("friends").addSnapshotListener { s, _ ->
            friends = s?.documents?.map { U(it.id, it.getString("username") ?: "?") } ?: friends
        }
        onDispose { l.remove() }
    }
    fun search(x: String) {
        q = x.lowercase()
        if (q.isBlank()) { res = listOf(); return }
        db.collection("users").orderBy("username").startAt(q).endAt(q + "\uf8ff").limit(10).get()
            .addOnSuccessListener { s ->
                res = s.documents.filter { it.id != me.uid }.map { U(it.id, it.getString("username") ?: "?") }
            }
    }
    fun add(u: U) {
        val users = db.collection("users")
        users.document(me.uid).collection("friends").document(u.uid).set(mapOf("username" to u.name))
        users.document(u.uid).collection("friends").document(me.uid).set(mapOf("username" to me.name))
        q = ""; res = listOf()
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Merhaba ${me.name}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = out) { Text("Çıkış") }
        }
        OutlinedTextField(q, { search(it) }, label = { Text("Kullanıcı ara") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        LazyColumn {
            if (q.isNotBlank()) {
                items(res) { u ->
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(u.name, Modifier.weight(1f))
                        Button(onClick = { add(u) }) { Text("Ekle") }
                    }
                }
            } else {
                items(friends) { u -> Text(u.name, Modifier.fillMaxWidth().clickable { open(u) }.padding(16.dp)) }
            }
        }
    }
}

@Composable
fun Chat(me: U, to: U, back: () -> Unit) {
    BackHandler(onBack = back)
    val id = listOf(me.uid, to.uid).sorted().joinToString("_")
    val col = db.collection("chats").document(id).collection("messages")
    var msgs by remember { mutableStateOf(listOf<M>()) }
    var t by remember { mutableStateOf("") }
    val ls = rememberLazyListState()
    DisposableEffect(id) {
        val l = col.orderBy("t").addSnapshotListener { s, _ ->
            msgs = s?.documents?.map { M(it.getString("from") ?: "", it.getString("text") ?: "") } ?: msgs
        }
        onDispose { l.remove() }
    }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) ls.animateScrollToItem(msgs.size - 1) }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back) { Text("←") }
            Text(to.name, style = MaterialTheme.typography.titleMedium)
        }
        LazyColumn(Modifier.weight(1f), state = ls) {
            items(msgs) { m ->
                val mine = m.from == me.uid
                Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.padding(4.dp)
                    ) { Text(m.text, Modifier.padding(10.dp)) }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(t, { t = it }, Modifier.weight(1f), placeholder = { Text("Mesaj") })
            Spacer(Modifier.width(6.dp))
            Button(onClick = {
                if (t.isNotBlank()) {
                    col.add(mapOf("from" to me.uid, "text" to t.trim(), "t" to System.currentTimeMillis()))
                    t = ""
                }
            }) { Text("Gönder") }
        }
    }
}
