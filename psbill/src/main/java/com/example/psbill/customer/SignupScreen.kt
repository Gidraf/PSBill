package com.example.psbill.customer

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/**
 * A new lounge signs up from the app: POST /auth/signup with business "gaming-lounge" (PSBill +
 * WiFi billing modules, free starter tokens). The reply is the same as a sign-in, so the owner is
 * signed straight in as the lounge's PSBill Admin. Errors come back with the field to mark.
 */
@Composable
fun PSBillSignupScreen(
    serverHost: String,
    initialEmail: String,
    onBack: () -> Unit,
    onSignedUp: (token: String, email: String, password: String) -> Unit,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val wide = isWide()
    BackHandler(onBack = onBack)

    var business by rememberSaveable { mutableStateOf("") }
    var first by rememberSaveable { mutableStateOf("") }
    var last by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var accept by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorField by remember { mutableStateOf<String?>(null) }
    val client = remember { OkHttpClient() }

    val emailOk = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email.trim())
    val phoneOk = Regex("^\\+?[0-9]{9,13}$").matches(phone.replace(" ", "").replace("-", ""))
    val ready = business.isNotBlank() && first.isNotBlank() && emailOk && phoneOk && password.length >= 8 && accept

    fun submit() {
        if (!ready || busy) return
        busy = true; error = null; errorField = null
        val body = JSONObject()
            .put("business", "gaming-lounge")
            .put("business_name", business.trim())
            .put("first_name", first.trim())
            .put("last_name", last.trim())
            .put("phone", phone.trim())
            .put("email", email.trim().lowercase())
            .put("password", password)
            .put("country", "Kenya")
            .put("accept_terms", true)
        val req = Request.Builder().url("$serverHost/auth/signup")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val activity = context as? androidx.activity.ComponentActivity
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity?.runOnUiThread { busy = false; error = "Could not reach the server. Check the internet and try again." }
            }

            override fun onResponse(call: Call, response: Response) {
                val text = response.body?.string().orEmpty()
                activity?.runOnUiThread {
                    busy = false
                    val o = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
                    if (response.isSuccessful) {
                        val token = o.optString("refresh_token").ifBlank { o.optString("access_token") }
                        if (token.isBlank()) error = "Your account was made. Sign in with your email and password."
                        else onSignedUp(token, email.trim().lowercase(), password)
                    } else {
                        error = o.optString("error").ifBlank { "Sign up failed (error ${response.code}). Try again." }
                        errorField = o.optString("field").ifBlank { null }
                    }
                }
            }
        })
    }

    val next = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) })
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1B1240), PSBillThemeColors.Canvas, PSBillThemeColors.Canvas)))
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to sign in", tint = PSBillThemeColors.TextPrimary) }
                Text("Create your lounge", color = PSBillThemeColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            }
            Text("Two minutes. You get the staff app, the TV app and WiFi billing. Pay per lounge each month - " +
                "KES 500 for up to 4 screens.", color = PSBillThemeColors.TextSecondary, fontSize = 13.sp)

            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(PSBillThemeColors.Surface)
                    .border(1.dp, PSBillThemeColors.Border, RoundedCornerShape(20.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(business, { business = it }, label = { Text("Lounge name") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.SportsEsports, null) }, modifier = Modifier.fillMaxWidth(),
                    isError = errorField == "business_name",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    keyboardActions = next)
                val nameFields: @Composable (Modifier, Modifier) -> Unit = { a, b ->
                    OutlinedTextField(first, { first = it }, label = { Text("Your first name") }, singleLine = true, modifier = a,
                        isError = errorField == "first_name",
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        keyboardActions = next)
                    OutlinedTextField(last, { last = it }, label = { Text("Last name") }, singleLine = true, modifier = b,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        keyboardActions = next)
                }
                if (wide) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { nameFields(Modifier.weight(1f), Modifier.weight(1f)) }
                else nameFields(Modifier.fillMaxWidth(), Modifier.fillMaxWidth())
                OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, placeholder = { Text("0712 345 678") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Phone, null) }, modifier = Modifier.fillMaxWidth(),
                    isError = errorField == "phone" || (phone.isNotBlank() && !phoneOk),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next), keyboardActions = next)
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Email, null) }, modifier = Modifier.fillMaxWidth(),
                    isError = errorField == "email" || (email.isNotBlank() && !emailOk),
                    supportingText = { Text("You sign in with this, on the app and the web") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next), keyboardActions = next)
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Lock, null) }, modifier = Modifier.fillMaxWidth(),
                    isError = errorField == "password" || (password.isNotEmpty() && password.length < 8),
                    supportingText = { Text("At least 8 characters") },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                if (showPassword) "Hide password" else "Show password")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); submit() }))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { accept = !accept }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = accept, onCheckedChange = { accept = it })
                    Text("I agree to the PSBill terms and the monthly price per lounge.", color = PSBillThemeColors.TextSecondary,
                        fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$serverHost/psbill/setup"))) }
                }) { Text("See prices and how setup works") }

                if (error != null) Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(PsTone.Bad.copy(alpha = 0.14f)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.ErrorOutline, null, tint = PsTone.Bad, modifier = Modifier.size(18.dp))
                    Text(error!!, color = PSBillThemeColors.TextPrimary, fontSize = 13.sp)
                }

                Button(onClick = { focus.clearFocus(); submit() }, enabled = ready && !busy,
                    modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PSBillThemeColors.PrimaryAccent)) {
                    if (busy) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp)); Text("Creating your lounge...")
                    } else Text("Create my lounge", fontWeight = FontWeight.SemiBold)
                }
            }
            TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Already have an account? Sign in")
            }
        }
    }
}
