/* Read-only macOS layout-data probe. Does not enable/switch layouts or claim physical-key testing. */
#include <Carbon/Carbon.h>
#include <ApplicationServices/ApplicationServices.h>
#include <stdio.h>
#include <string.h>
static void utf8(CFStringRef s, char *out, size_t size) {
    if (!s || !CFStringGetCString(s, out, size, kCFStringEncodingUTF8)) out[0] = 0;
}
int main(void) {
    CFArrayRef sources = TISCreateInputSourceList(NULL, true);
    if (!sources) return 1;
    TISInputSourceRef current = TISCopyCurrentKeyboardInputSource();
    char id[256]; utf8(TISGetInputSourceProperty(current, kTISPropertyInputSourceID), id, sizeof id);
    printf("Current input source\t%s\nAX probe process trusted\t%s\n", id, AXIsProcessTrusted() ? "true" : "false");
    printf("Method\tUCKeyTranslate on installed Apple layout data; no source switching; no physical events\n");
    unsigned found = 0;
    for (CFIndex i = 0; i < CFArrayGetCount(sources); i++) {
        TISInputSourceRef source = (TISInputSourceRef)CFArrayGetValueAtIndex(sources, i);
        utf8(TISGetInputSourceProperty(source, kTISPropertyInputSourceID), id, sizeof id);
        if (strcmp(id,"com.apple.keylayout.US") && !strstr(id,"Italian") && !strstr(id,"Swiss")) continue;
        CFDataRef data = TISGetInputSourceProperty(source, kTISPropertyUnicodeKeyLayoutData);
        printf("Layout\t%s\tenabled=%s\tunicode-data=%s\n", id,
               TISGetInputSourceProperty(source,kTISPropertyInputSourceIsEnabled)==kCFBooleanTrue?"true":"false",data?"true":"false");
        if (!data) continue;
        found++;
        const UCKeyboardLayout *layout = (const UCKeyboardLayout *)CFDataGetBytePtr(data);
        for (UInt16 key = 0; key < 51; key++) {
            for (unsigned mode = 0; mode < 3; mode++) {
                UInt32 dead = 0; UniChar text[16]; UniCharCount length = 0;
                UInt32 modifier = mode == 1 ? shiftKey >> 8 : mode == 2 ? optionKey >> 8 : 0;
                OSStatus status = UCKeyTranslate(layout,key,kUCKeyActionDown,modifier,LMGetKbdType(),0,&dead,16,&length,text);
                CFStringRef string = CFStringCreateWithCharacters(NULL,text,length); char value[128]; utf8(string,value,sizeof value); CFRelease(string);
                if (status) { fprintf(stderr,"UCKeyTranslate failed: %d\n",(int)status); CFRelease(current); CFRelease(sources); return 2; }
                if (length == 1 && text[0] < 32) snprintf(value,sizeof value,"U+%04X",text[0]);
                printf("Key\t%s\t%u\t%s\t%s\tdead=%u\n",id,key,mode==1?"Shift":mode==2?"Option":"plain",value,(unsigned)dead);
                if (dead) {
                    status = UCKeyTranslate(layout,kVK_ANSI_E,kUCKeyActionDown,0,LMGetKbdType(),0,&dead,16,&length,text);
                    if(status) return 3;
                    string=CFStringCreateWithCharacters(NULL,text,length); utf8(string,value,sizeof value); CFRelease(string);
                    printf("Dead+E\t%s\t%s\tremaining=%u\n",id,value,(unsigned)dead);
                }
            }
        }
    }
    CFRelease(current); CFRelease(sources);
    printf("Layouts probed\t%u\n",found);
    return found ? 0 : 4;
}
