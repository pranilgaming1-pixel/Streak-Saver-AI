package com.streaksaver.ai
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val bg=Color(0xFF080B14)
private val panel=Color(0xFF121827)
private val violet=Color(0xFF9D7BFF)
private val cyan=Color(0xFF50D9E8)
private val muted=Color(0xFF9AA6BD)
class MainActivity:ComponentActivity(){
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);window.statusBarColor=android.graphics.Color.rgb(8,11,20);window.navigationBarColor=android.graphics.Color.rgb(8,11,20);setContent{EzoneApp()}}
}
private data class Post(val id:Int,val name:String,val handle:String,val letter:String,val caption:String,val art:String,val likes:Int,val liked:Boolean=false)
@Composable private fun EzoneApp(){
 var tab by remember{mutableStateOf("Home")}
 var compose by remember{mutableStateOf(false)}
 var draft by remember{mutableStateOf("")}
 var query by remember{mutableStateOf("")}
 var chat by remember{mutableStateOf<String?>(null)}
 var privateAccount by remember{mutableStateOf(false)}
 var posts by remember{mutableStateOf(listOf(Post(1,"Maya","@maya.v","M","Golden hour, good people, and absolutely no plans. ✨","🌅",128),Post(2,"Aarav","@aarav.exe","A","Weekend mode: activated. Who's coming? 🚀","🏍️",86),Post(3,"Sofia","@sofia.jpg","S","Little moments make the best memories.","🌸",204)))}
 val friends=listOf("Maya","Aarav","Sofia","Rohan","Anika")
 MaterialTheme(colorScheme=darkColorScheme(background=bg,surface=panel,primary=violet,secondary=cyan,onBackground=Color.White,onSurface=Color.White)){
  Surface(Modifier.fillMaxSize(),color=bg){
   if(chat!=null) Chat(chat!!,{chat=null}) else Column(Modifier.fillMaxSize()){
    when(tab){
     "Home"->LazyColumn(Modifier.weight(1f).padding(horizontal=16.dp),contentPadding=PaddingValues(top=18.dp,bottom=18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
      item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("EZONE",color=violet,fontSize=12.sp,fontWeight=FontWeight.Black,letterSpacing=4.sp);Text("Your circle. Your world.",color=Color.White,fontSize=23.sp,fontWeight=FontWeight.ExtraBold)};Text("✦",color=cyan,fontSize=28.sp)}}
      item{Text("YOUR CIRCLE",color=muted,fontSize=10.sp,letterSpacing=2.sp);Spacer(Modifier.height(10.dp));Row(horizontalArrangement=Arrangement.spacedBy(14.dp)){(listOf("You" to "+")+friends.map{it to it.take(1)}).forEachIndexed{idx,p->Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.clickable{if(idx>0)chat=p.first}){Avatar(p.second,54);Text(p.first,color=muted,fontSize=10.sp)}}}}
      item{Row(verticalAlignment=Alignment.CenterVertically){Text("THE FEED",color=Color.White,fontWeight=FontWeight.Bold,fontSize=17.sp,modifier=Modifier.weight(1f));TextButton(onClick={compose=true}){Text("+ Create",color=cyan)}}}
      items(posts,key={it.id}){p->Card(colors=CardDefaults.cardColors(containerColor=panel),shape=RoundedCornerShape(22.dp)){Column(Modifier.padding(14.dp)){Row(verticalAlignment=Alignment.CenterVertically){Avatar(p.letter,40);Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(p.name,color=Color.White,fontWeight=FontWeight.Bold);Text(p.handle+" · recently",color=muted,fontSize=10.sp)};Text("•••",color=muted)};Spacer(Modifier.height(12.dp));Text(p.caption,color=Color(0xFFE6EAF4),fontSize=14.sp);Spacer(Modifier.height(12.dp));Box(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(Color(0xFF292447),Color(0xFF142A3A)))),contentAlignment=Alignment.Center){Text(p.art,fontSize=60.sp)};TextButton(onClick={posts=posts.map{if(it.id==p.id)it.copy(liked=!it.liked,likes=it.likes+if(it.liked)-1 else 1)else it}}){Text((if(p.liked)"♥" else "♡")+"  "+p.likes,color=if(p.liked)Color(0xFFFF6D9A)else muted)}}}}
      item{Text("You're all caught up ✨",color=muted,fontSize=12.sp)}
     }
     "Explore"->Column(Modifier.weight(1f).padding(20.dp)){Text("Discover",color=Color.White,fontSize=28.sp,fontWeight=FontWeight.ExtraBold);Text("Find people and grow your circle.",color=muted);Spacer(Modifier.height(16.dp));OutlinedTextField(query,{query=it},modifier=Modifier.fillMaxWidth(),placeholder={Text("Search people")},singleLine=true);Spacer(Modifier.height(14.dp));friends.filter{it.contains(query,true)}.forEach{n->Row(Modifier.fillMaxWidth().clickable{chat=n}.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){Avatar(n.take(1),44);Spacer(Modifier.width(12.dp));Text(n,color=Color.White,modifier=Modifier.weight(1f));Text("Message →",color=cyan)}}}
     "Messages"->Column(Modifier.weight(1f).padding(20.dp)){Text("Messages",color=Color.White,fontSize=28.sp,fontWeight=FontWeight.ExtraBold);Text("Your private conversations",color=muted);Spacer(Modifier.height(15.dp));friends.forEach{n->Row(Modifier.fillMaxWidth().clickable{chat=n}.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically){Avatar(n.take(1),48);Spacer(Modifier.width(12.dp));Column{Text(n,color=Color.White,fontWeight=FontWeight.Bold);Text("Tap to open conversation",color=muted,fontSize=11.sp)}};HorizontalDivider(color=panel)};Text("Local preview only: messages are not delivered to other devices until a backend is connected.",color=muted,fontSize=11.sp)}
     else->Column(Modifier.weight(1f).padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally){Text("Your profile",color=Color.White,fontSize=28.sp,fontWeight=FontWeight.ExtraBold);Spacer(Modifier.height(22.dp));Avatar("Y",90);Text("Your Ezone",color=Color.White,fontSize=20.sp,fontWeight=FontWeight.Bold);Text("@you.ezone",color=muted);Text("Making memories with my people ✨",color=muted);Spacer(Modifier.height(24.dp));Row(horizontalArrangement=Arrangement.spacedBy(32.dp)){Text(posts.size.toString()+" Posts",color=Color.White);Text("24 Friends",color=Color.White);Text("08 Moments",color=Color.White)};Spacer(Modifier.height(28.dp));Text("ACCOUNT VISIBILITY",color=muted,fontSize=10.sp,letterSpacing=2.sp);Row(verticalAlignment=Alignment.CenterVertically){Text(if(privateAccount)"Private account" else "Public account",color=Color.White,modifier=Modifier.weight(1f));Switch(privateAccount,{privateAccount=it})};Text("Privacy setting is a local preview; server-side enforcement needs backend setup.",color=muted,fontSize=10.sp)}
    }
    NavigationBar(containerColor=Color(0xFF0D1220)){listOf("Home" to "⌂","Explore" to "⌕","Messages" to "✉","Profile" to "◉").forEach{(n,g)->NavigationBarItem(selected=tab==n,onClick={tab=n},icon={Text(g,fontSize=22.sp)},label={Text(n,fontSize=10.sp)},colors=NavigationBarItemDefaults.colors(selectedIconColor=violet,selectedTextColor=violet,indicatorColor=Color(0xFF27213F),unselectedTextColor=muted))}}
   }
  }
 }
 if(compose)AlertDialog(onDismissRequest={compose=false},containerColor=panel,title={Text("Create a moment",color=Color.White)},text={OutlinedTextField(draft,{draft=it},placeholder={Text("What's happening?")},minLines=3)},confirmButton={TextButton(onClick={if(draft.isNotBlank())posts=listOf(Post((posts.maxOfOrNull{it.id}?:0)+1,"You","@you","Y",draft.trim(),"💫",0))+posts;draft="";compose=false}){Text("Post",color=violet)}},dismissButton={TextButton(onClick={compose=false}){Text("Cancel")}})
}
@Composable private fun Chat(name:String,onBack:()->Unit){
 var msg by remember{mutableStateOf("")};var messages by remember{mutableStateOf(listOf("Hey! How's your day going?","Pretty good! What about you?"))}
 Column(Modifier.fillMaxSize().padding(16.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text("‹",color=violet,fontSize=32.sp,modifier=Modifier.clickable{onBack()});Avatar(name.take(1),42);Spacer(Modifier.width(10.dp));Column{Text(name,color=Color.White,fontWeight=FontWeight.Bold);Text("EZONE CHAT · PREVIEW",color=cyan,fontSize=9.sp)}};Spacer(Modifier.height(20.dp));Column(Modifier.weight(1f),verticalArrangement=Arrangement.Bottom){messages.forEachIndexed{i,m->Row(Modifier.fillMaxWidth(),horizontalArrangement=if(i%2==0)Arrangement.Start else Arrangement.End){Surface(color=if(i%2==0)Color(0xFF1A2234)else Color(0xFF453477),shape=RoundedCornerShape(18.dp)){Text(m,color=Color.White,modifier=Modifier.padding(13.dp))}};Spacer(Modifier.height(8.dp))}};Row(verticalAlignment=Alignment.CenterVertically){OutlinedTextField(msg,{msg=it},modifier=Modifier.weight(1f),placeholder={Text("Write a message…")},singleLine=true);Button(onClick={if(msg.isNotBlank()){messages=messages+msg.trim();msg=""}}){Text("➤")}};Text("Local preview only — no message is sent to another device.",color=muted,fontSize=10.sp)}
}
@Composable private fun Avatar(letter:String,size:Int){Box(Modifier.size(size.dp).clip(CircleShape).background(Brush.linearGradient(listOf(violet,Color(0xFF315D79)))),contentAlignment=Alignment.Center){Text(letter.take(1).uppercase(),color=Color.White,fontWeight=FontWeight.Bold,fontSize=(size/2).sp)}}
