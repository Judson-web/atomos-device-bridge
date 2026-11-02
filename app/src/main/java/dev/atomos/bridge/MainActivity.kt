package dev.atomos.bridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val Ink=Color(0xFF080A11);private val Panel=Color(0xFF141927);private val Violet=Color(0xFF9B8CFF);private val Muted=Color(0xFF9AA4B9)
class MainActivity:ComponentActivity(){
    private var callback by mutableStateOf<Uri?>(null)
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);callback=intent?.data;val store=SecureStore(this);val api=AtomosApi()
        setContent { AtomosApp(store,api,callback,{callback=null;startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(it)))},{startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))},{number,body->startActivity(Intent(Intent.ACTION_SENDTO,Uri.parse("smsto:"+Uri.encode(number))).putExtra("sms_body",body))},{ContextCompat.startForegroundService(this,Intent(this,CloudCommandService::class.java).setAction(CloudCommandService.ACTION_START))},{startService(Intent(this,CloudCommandService::class.java).setAction(CloudCommandService.ACTION_STOP))}) }
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);callback=intent.data}
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AtomosApp(store:SecureStore,api:AtomosApi,callback:Uri?,openOAuth:(String)->Unit,openAccessibility:()->Unit,composeSms:(String,String)->Unit,startCloud:()->Unit,stopCloud:()->Unit){
    val scope=rememberCoroutineScope();var stage by remember { mutableStateOf(if(store.get("access")!=null)"signed" else "github") };var page by remember { mutableStateOf("Home") };var drawer by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) };var message by remember { mutableStateOf("Your personal cloud-connected device bridge.") }
    var deviceId by remember { mutableStateOf(store.get("device_id").orEmpty()) };var token by remember { mutableStateOf(store.get("device_token")) }
    var devices by remember { mutableStateOf(listOf<JSONObject>()) };var commands by remember { mutableStateOf(listOf<JSONObject>()) };var cloudActive by remember { mutableStateOf(false) }
    var x by remember { mutableStateOf("300") };var y by remember { mutableStateOf("600") };var inputText by remember { mutableStateOf("") };var smsNumber by remember { mutableStateOf("") };var smsBody by remember { mutableStateOf("") };var confirmSms by remember { mutableStateOf(false) };var authFailed by remember { mutableStateOf(false) }
    val owner=LocalLifecycleOwner.current
    fun runTask(block:suspend()->Unit){if(busy)return;busy=true;scope.launch{try{block()}catch(e:Exception){message=e.message?: "Request failed"}finally{busy=false}}}
    fun send(type:String,payload:JSONObject=JSONObject()){runTask{val r=withContext(Dispatchers.IO){api.callAsUser("create_command",store,JSONObject().put("device_id",deviceId).put("command_type",type).put("payload",payload))};message="Command queued: $type";}}
    LaunchedEffect(callback){val uri=callback?:return@LaunchedEffect;val code=uri.getQueryParameter("code");val verifier=store.get("github_pkce_verifier");try{require(!code.isNullOrBlank()&&!verifier.isNullOrBlank());val t=withContext(Dispatchers.IO){api.exchangeGitHubCode(code,verifier)};val allowed=withContext(Dispatchers.IO){api.githubIdentityAllowed(t.accessToken)};if(!allowed){store.remove("access","refresh","github_pkce_verifier");stage="github";authFailed=true;message="This GitHub account is not authorized to use Atomos."}else{store.put("access",t.accessToken);store.put("refresh",t.refreshToken);store.remove("github_pkce_verifier");stage="signed";message="Authorized GitHub account connected."}}catch(e:Exception){store.remove("access","refresh","github_pkce_verifier");stage="github";authFailed=true;message="Authentication failed. Only the registered GitHub account can access Atomos."}}
    LaunchedEffect(Unit){val existing=store.get("access");if(existing!=null){val allowed=withContext(Dispatchers.IO){runCatching{api.githubIdentityAllowed(existing)}.getOrDefault(false)};if(!allowed){store.remove("access","refresh");stage="github";authFailed=true;message="Saved session is not authorized. Sign in with the registered GitHub account."}}}
    LaunchedEffect(token){if(token!=null){try{val r=withContext(Dispatchers.IO){api.call("device_status",device=token)};message=if(r.optBoolean("authenticated"))"Cloud device verified" else "Device unavailable"}catch(e:Exception){message=e.message?: "Device offline"}}}
    DisposableEffect(owner){val o=LifecycleEventObserver{_,e->if(e==Lifecycle.Event.ON_RESUME)cloudActive=CloudCommandService::class.java.let { false }};owner.lifecycle.addObserver(o);onDispose{owner.lifecycle.removeObserver(o)}}
    val nav=listOf("Home","Control","Devices","Settings","About")
    val drawerState=rememberDrawerState(DrawerValue.Closed)
    LaunchedEffect(drawer){ if(drawer) drawerState.open() else drawerState.close() }
    MaterialTheme(colorScheme=darkColorScheme(primary=Violet,background=Ink,surface=Panel,onSurface=Color.White)){
        ModalNavigationDrawer(drawerState=drawerState,drawerContent={ModalDrawerSheet(drawerContainerColor=Panel){
            Column(Modifier.fillMaxWidth().padding(22.dp)){Text("ATOMOS",color=Violet,fontWeight=FontWeight.Black,letterSpacing=4.sp,fontSize=13.sp);Text("Cloud device bridge",color=Muted,fontSize=12.sp)}
            nav.forEach{item->NavigationDrawerItem(label={Text(item)},selected=page==item,onClick={page=item;drawer=false},icon={Icon(when(item){"Home"->Icons.Default.Home;"Control"->Icons.Default.Gamepad;"Devices"->Icons.Default.Devices;"Settings"->Icons.Default.Settings;else->Icons.Default.Info},null)},modifier=Modifier.padding(horizontal=12.dp))}
        }}) {
            Scaffold(
                containerColor = Ink,
                topBar = {
                    CenterAlignedTopAppBar(
                        title = { Text("Atomos", fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = { drawer = true }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                            }
                        },
                        actions = {
                            Icon(Icons.Default.Brightness2, contentDescription = null, tint = Violet)
                            Spacer(Modifier.width(12.dp))
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = Ink,
                            titleContentColor = Color.White
                        )
                    )
                },
                bottomBar = {
                    NavigationBar(containerColor = Panel) {
                        nav.take(4).forEach { item ->
                            NavigationBarItem(
                                selected = page == item,
                                onClick = { page = item },
                                icon = {
                                    Icon(
                                        when (item) {
                                            "Home" -> Icons.Default.Home
                                            "Control" -> Icons.Default.Gamepad
                                            "Devices" -> Icons.Default.Devices
                                            else -> Icons.Default.Settings
                                        },
                                        contentDescription = item
                                    )
                                },
                                label = { Text(item) }
                            )
                        }
                    }
                }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                    when(page){
                        "Home"->{
                            Text("YOUR DEVICES, IN ORBIT.",color=Violet,fontSize=11.sp,letterSpacing=2.sp,fontWeight=FontWeight.Bold)
                            Text("Your phone, connected.",color=Color.White,fontSize=27.sp,fontWeight=FontWeight.Bold)
                            Card(colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                                Row(verticalAlignment=Alignment.CenterVertically){Text("DEVICE CONNECTION",color=Muted,fontSize=10.sp,letterSpacing=2.sp);Spacer(Modifier.weight(1f));Text(if(token!=null)"REGISTERED" else "NOT PAIRED",color=if(token!=null)Color(0xFF72E1B6) else Color(0xFFFFC477),fontWeight=FontWeight.Bold,fontSize=11.sp)}
                                Text(if(token!=null)"Secure device linked" else "Connect this phone",color=Color.White,fontSize=21.sp,fontWeight=FontWeight.Bold);Text(message,color=Muted,fontSize=13.sp)
                                if(deviceId.isNotBlank())Text("Device ID  $deviceId",color=Muted,fontSize=10.sp)
                            }}
                            if(stage=="github")Button(onClick={val v=api.newPkceVerifier();store.put("github_pkce_verifier",v);openOAuth(api.githubAuthorizationUrl(v))},modifier=Modifier.fillMaxWidth()){Text("Continue with GitHub")}
                            else if(token==null)Button(enabled=!busy,onClick={runTask{val c=withContext(Dispatchers.IO){api.callAsUser("create_pairing",store,JSONObject().put("device_name","Android phone").put("app_version","0.3.0"))};val r=withContext(Dispatchers.IO){api.callAsUser("claim_pairing",store,JSONObject().put("code",c.getString("code")).put("device_name","Android phone"))};deviceId=r.getString("device_id");token=r.getString("device_token");store.put("device_id",deviceId);store.put("device_token",token!!);message="Phone paired to your GitHub account."}},modifier=Modifier.fillMaxWidth()){Text("Register this phone")}
                            else{Button(onClick={startCloud();cloudActive=true;message="Cloud command listener started. Keep the persistent notification active."},modifier=Modifier.fillMaxWidth()){Text("Start cloud control")};OutlinedButton(onClick={stopCloud();cloudActive=false},modifier=Modifier.fillMaxWidth()){Text("Stop cloud control")}}
                            Text("Cloud control runs only while you explicitly start the foreground connection. Android shows a persistent notification while it is active.",color=Muted,fontSize=11.sp)
                        }
                        "Control"->{
                            Text("REMOTE CONTROL",color=Violet,fontSize=11.sp,letterSpacing=2.sp,fontWeight=FontWeight.Bold);Text("Command console",color=Color.White,fontSize=25.sp,fontWeight=FontWeight.Bold)
                            Text("Commands are delivered through your authenticated cloud session and executed only while the phone's control service is active.",color=Muted,fontSize=13.sp)
                            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Button(enabled=token!=null&&!busy,onClick={send("inspect")},modifier=Modifier.weight(1f)){Text("Inspect screen")};OutlinedButton(enabled=token!=null&&!busy,onClick={send("back")},modifier=Modifier.weight(1f)){Text("Back")}}
                            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){OutlinedButton(enabled=token!=null&&!busy,onClick={send("home")},modifier=Modifier.weight(1f)){Text("Home")};OutlinedButton(enabled=token!=null&&!busy,onClick={send("notifications")},modifier=Modifier.weight(1f)){Text("Notifications")}}
                            Text("Tap coordinates",color=Color.White,fontWeight=FontWeight.SemiBold)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(x,{x=it},label={Text("X")},modifier=Modifier.weight(1f));OutlinedTextField(y,{y=it},label={Text("Y")},modifier=Modifier.weight(1f))}
                            Button(enabled=token!=null&&!busy,onClick={send("tap",JSONObject().put("x",x.toDoubleOrNull()).put("y",y.toDoubleOrNull()))},modifier=Modifier.fillMaxWidth()){Text("Tap screen")}
                            OutlinedTextField(inputText,{inputText=it},label={Text("Text to enter into focused field")},modifier=Modifier.fillMaxWidth())
                            Button(enabled=token!=null&&!busy&&inputText.length<=4000,onClick={send("type",JSONObject().put("text",inputText))},modifier=Modifier.fillMaxWidth()){Text("Type text")}
                            Divider(color=Muted.copy(alpha=.25f))
                            Text("SMS • compose securely",color=Color.White,fontWeight=FontWeight.SemiBold)
                            Text("Atomos opens your SMS app with the recipient and message filled in. You review and press Send yourself. Inbox and OTP contents are never read.",color=Muted,fontSize=12.sp)
                            OutlinedTextField(smsNumber,{smsNumber=it},label={Text("Phone number")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                            OutlinedTextField(smsBody,{smsBody=it},label={Text("Message")},modifier=Modifier.fillMaxWidth(),minLines=2)
                            Button(enabled=smsNumber.trim().isNotEmpty()&&smsBody.isNotBlank(),onClick={confirmSms=true},modifier=Modifier.fillMaxWidth()){Text("Review SMS")}
                        }
                        "Devices"->{
                            Text("DEVICE FLEET",color=Violet,fontSize=11.sp,letterSpacing=2.sp,fontWeight=FontWeight.Bold);Text("Your devices",color=Color.White,fontSize=25.sp,fontWeight=FontWeight.Bold)
                            Button(enabled=stage=="signed"&&!busy,onClick={runTask{val r=withContext(Dispatchers.IO){api.callAsUser("list_devices",store)};val a=r.optJSONArray("devices");devices=(0 until (a?.length()?:0)).mapNotNull{a?.optJSONObject(it)}}},modifier=Modifier.fillMaxWidth()){Text("Refresh device list")}
                            devices.forEach{d->Card(colors=CardDefaults.cardColors(containerColor=Panel)){Column(Modifier.padding(16.dp)){Text(d.optString("device_name","Android device"),color=Color.White,fontWeight=FontWeight.Bold);Text(d.optString("status"),color=Muted);Text(d.optString("id"),color=Muted,fontSize=10.sp)}}}
                            Text("Devices registered with this GitHub-linked Atomos account appear here. Sign in with the same account on each control client.",color=Muted,fontSize=12.sp)
                        }
                        "Settings"->{Text("SETTINGS",color=Violet,fontSize=11.sp,letterSpacing=2.sp);Text("Connection & permissions",color=Color.White,fontSize=24.sp,fontWeight=FontWeight.Bold);Button(onClick=openAccessibility,modifier=Modifier.fillMaxWidth()){Text("Accessibility settings")};OutlinedButton(onClick={store.remove("access","refresh");stage="github";message="Signed out. Device remains registered."},modifier=Modifier.fillMaxWidth()){Text("Sign out of GitHub")};Text("Notification access is optional. Screen capture will request Android MediaProjection consent when implemented.",color=Muted,fontSize=12.sp)}
                        else->{Text("ABOUT ATOMOS",color=Violet,fontSize=11.sp,letterSpacing=2.sp);Text("A deliberate bridge between your devices and your AI workspace.",color=Color.White,fontSize=24.sp,fontWeight=FontWeight.Bold);Text("Version 0.3.0 • GitHub identity • encrypted device credentials • cloud command relay • Android AccessibilityService",color=Muted,fontSize=13.sp)}
                    }
                    if(confirmSms)AlertDialog(onDismissRequest={confirmSms=false},title={Text("Open SMS composer?")},text={Text("Atomos will open your messaging app. Nothing is sent until you press Send there.")},confirmButton={TextButton(onClick={confirmSms=false;composeSms(smsNumber.trim(),smsBody)}){Text("Continue")}},dismissButton={TextButton(onClick={confirmSms=false}){Text("Cancel")}})
                    if(authFailed)AlertDialog(onDismissRequest={authFailed=false},title={Text("Authentication failed")},text={Text("Access is restricted to the registered GitHub account (ID 85796566). Sign in with the authorized account.")},confirmButton={TextButton(onClick={authFailed=false}){Text("Close")}})
                    if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(message,color=Muted,fontSize=11.sp)
                }
            }
        }
    }
}