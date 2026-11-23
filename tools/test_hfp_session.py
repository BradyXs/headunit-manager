#!/usr/bin/env python3
"""Host checks of the actual automatic HFP lease and gate classifier, not OEM hardware."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SDK_ROOT = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
if not SDK_ROOT:
    raise SystemExit('Set ANDROID_SDK_ROOT or ANDROID_HOME')
ANDROID_JAR = Path(SDK_ROOT) / 'platforms/android-19/android.jar'
STUBS = {
    'android/content/ContentResolver.java': '''package android.content; public class ContentResolver {}''',
    'android/content/Context.java': '''package android.content;
public abstract class Context { public Context getApplicationContext(){return this;}
public ContentResolver getContentResolver(){return new ContentResolver();} }''',
    'android/os/RemoteException.java': '''package android.os;
public class RemoteException extends Exception { public RemoteException(){} public RemoteException(String s){super(s);} }''',
    'android/os/IBinder.java': '''package android.os;
public interface IBinder { interface DeathRecipient { void binderDied(); }
boolean isBinderAlive(); void linkToDeath(DeathRecipient d,int flags) throws RemoteException;
boolean unlinkToDeath(DeathRecipient d,int flags); }''',
    'android/os/Looper.java': '''package android.os;
public class Looper { public static Looper getMainLooper(){return new Looper();} }''',
    'android/os/SystemClock.java': '''package android.os;
public class SystemClock { public static long now=100; public static long elapsedRealtime(){return now;} }''',
    'android/os/Handler.java': '''package android.os;
public class Handler { public static Runnable pending; public Handler(Looper l){}
public boolean postDelayed(Runnable r,long delay){pending=r; return true;}
public void removeCallbacks(Runnable r){if(pending==r)pending=null;}
public static void tick(){Runnable r=pending; pending=null; if(r!=null)r.run();} }''',
    'android/provider/Settings.java': '''package android.provider;
import android.content.ContentResolver;
public class Settings { public static class Global { public static String value; public static boolean fail;
public static boolean putString(ContentResolver c,String k,String v){if(fail)return false; value=v;return true;}
public static String getString(ContentResolver c,String k){return value;} } }''',
    'android/util/Log.java': '''package android.util;
public class Log { public static int i(String t,String s){return 0;} }''',
}
TEST = '''package com.bradyxu.headunitmanager;
import android.content.Context;
import android.os.*;
import android.provider.Settings;
public class LeaseTest {
  static final String A="12:34:56:78:90:AB", B="12:34:56:78:90:AC";
  static final Context C=new Context(){};
  static int checks;
  static class Token implements IBinder {
    boolean alive=true; DeathRecipient death;
    public boolean isBinderAlive(){return alive;}
    public void linkToDeath(DeathRecipient d,int f){death=d;}
    public boolean unlinkToDeath(DeathRecipient d,int f){death=null; return true;}
    void die(){alive=false; if(death!=null)death.binderDied();}
  }
  static void check(boolean ok,String label){checks++; if(!ok)throw new AssertionError(label);}
  static boolean held(Token t){return HfpSessionLease.isActiveHold(t,A,HfpSessionLease.authorizationEpoch());}
  static void activate(Token t){HfpSessionLease.beginAutomatic(t,A,HfpSessionLease.authorizationEpoch(),Settings.Global.value);}
  static Token fresh(){Token t=new Token(); check(HfpSessionLease.pulse(C,t,A,true)==1,"prearm"); return t;}
  public static void main(String[] args){
    Token first=new Token();
    check(HfpSessionLease.pulse(C,first,A,false)==-1,"wireless rejected");
    check(HfpSessionLease.pulse(C,first,"bad",true)==-1,"invalid address rejected");
    check(HfpSessionLease.pulse(C,first,A,true)==1 && held(first),"wired session prearms gate");
    check(Settings.Global.value!=null && Settings.Global.value.startsWith(A+"|"),"gate belongs to session candidate");
    check(HfpSessionLease.claimAutomaticAttempt(first),"first handoff attempt claimed");
    check(!HfpSessionLease.claimAutomaticAttempt(first),"handoff attempt cannot repeat");
    long epoch=HfpSessionLease.authorizationEpoch();
    String old=Settings.Global.value;
    HfpSessionLease.beginAutomatic(first,B,epoch,old);
    check(held(first) && old.equals(Settings.Global.value),"mismatched address cannot change gate");
    activate(first);
    check(held(first),"automatic hold succeeds with same address");
    for(int i=0;i<35;i++){SystemClock.now+=3000; HfpSessionLease.pulse(C,first,A,true); Handler.tick();}
    check(held(first) && Settings.Global.value.contains("|"+SystemClock.now+"|"),"heartbeats refresh beyond 90s");
    check(HfpSessionLease.pulse(C,new Token(),A,true)==0,"second owner cannot replace live session");
    check(HfpSessionLease.release(first,"client_session_ended"),"normal end schedules restore");
    check(Settings.Global.value==null && A.equals(HfpSessionLease.restorableAddress()),"normal end clears gate and retains same phone");
    check(HfpSessionLease.consumeRestorableAddress(A),"restore can be consumed once");
    check(!HfpSessionLease.consumeRestorableAddress(A),"restore cannot be reused");
    Token oldToken=fresh();
    SystemClock.now+=16000; Handler.tick();
    check(Settings.Global.value==null && HfpSessionLease.currentToken()==null,"watchdog clears stale gate");
    Token next=fresh();
    HfpSessionLease.release(oldToken,"late_release");
    check(held(next),"old token cannot release new session");
    next.die();
    check(Settings.Global.value==null && HfpSessionLease.currentToken()==null,"binder death clears gate");
    Token external=fresh();
    Settings.Global.value="external"; Handler.tick();
    check("external".equals(Settings.Global.value) && HfpSessionLease.currentToken()==null,"external gate not overwritten");
    Settings.Global.value=null; Settings.Global.fail=true;
    check(HfpSessionLease.pulse(C,new Token(),A,true)==-1,"failed prearm is rejected");
    check(HfpSessionLease.currentToken()==null,"failed prearm leaves no owner");
    Settings.Global.fail=false;
    String other=B+"|"+SystemClock.now+"|"+System.currentTimeMillis();
    Settings.Global.value=other;
    check(HfpSessionLease.pulse(C,new Token(),A,true)==0 && other.equals(Settings.Global.value),"active other-phone gate preserved");
    Settings.Global.value=null;
    Token identity=fresh();
    check(HfpSessionLease.pulse(C,identity,B,true)==-1 && Settings.Global.value==null,"identity change revokes gate");
    Token cancelled=fresh(); long previous=HfpSessionLease.authorizationEpoch();
    HfpSessionLease.stopHold();
    HfpSessionLease.beginAutomatic(cancelled,A,previous,Settings.Global.value);
    check(!held(cancelled),"old epoch cannot restore an aborted hold");
    check(!HfpSessionLease.release(cancelled,"client_session_ended"),"aborted hold does not schedule automatic restore");
    Token last=fresh();
    check(HfpSessionLease.restorableAddress()==null,"new session clears previous restore candidate");
    HfpSessionLease.release(last,"client_session_ended");
    System.out.println("PASS automatic HFP lease checks="+checks+" (Android stubs; no OEM hardware)");
  }
}'''

if not ANDROID_JAR.is_file():
    raise SystemExit('Android API 19 android.jar not found; set ANDROID_SDK_ROOT')
with tempfile.TemporaryDirectory(prefix='hfp-session-test-') as directory:
    out = Path(directory)
    for name, source in STUBS.items():
        path = out / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source)
    test = out / 'com/bradyxu/headunitmanager/LeaseTest.java'
    test.parent.mkdir(parents=True, exist_ok=True)
    test.write_text(TEST)
    # Compile production classes against the real API 19 signatures, then replace only the
    # Android runtime objects with deterministic stubs. The HsaeBluetooth classifier is real.
    source_dir = ROOT / 'app/src/main/java/com/bradyxu/headunitmanager'
    subprocess.run(['javac', '--release', '8', '-cp', str(ANDROID_JAR), '-d', str(out),
                    str(source_dir / 'HsaeBluetooth.java'), str(source_dir / 'HfpSessionLease.java')], check=True)
    sources = list(out.rglob('*.java'))
    subprocess.run(['javac', '--release', '8', '-cp', f'{out}{os.pathsep}{ANDROID_JAR}',
                    '-d', str(out), *map(str, sources)], check=True)
    subprocess.run(['java', '-cp', f'{out}{os.pathsep}{ANDROID_JAR}',
                    'com.bradyxu.headunitmanager.LeaseTest'], check=True)
