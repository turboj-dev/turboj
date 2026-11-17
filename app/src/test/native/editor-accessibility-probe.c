/* Read only the named fixture editor in the explicitly supplied test process. No global AX scan. */
#include <ApplicationServices/ApplicationServices.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static unsigned visited;
static void attribute(AXUIElementRef element, CFStringRef key, char *buffer, size_t capacity) {
    CFTypeRef value = NULL; buffer[0] = 0;
    if (AXUIElementCopyAttributeValue(element, key, &value) != kAXErrorSuccess || !value) return;
    if (CFGetTypeID(value) == CFStringGetTypeID()) CFStringGetCString(value, buffer, capacity, kCFStringEncodingUTF8);
    CFRelease(value);
}
static int inspect(AXUIElementRef element, unsigned depth) {
    if (++visited > 4000 || depth > 40) return 0;
    char role[256], title[512], value[8192], selected[1024];
    attribute(element,kAXRoleAttribute,role,sizeof role);
    attribute(element,kAXTitleAttribute,title,sizeof title);
    attribute(element,kAXValueAttribute,value,sizeof value);
    if(strstr(role,"Text") || strstr(title,"editor"))
        printf("Candidate\t%s\t%s\t%s\n",role,title,value);
    if (strstr(value,"AccessibilityFixture")) {
        attribute(element,kAXSelectedTextAttribute,selected,sizeof selected);
        printf("Role\t%s\nTitle\t%s\nValue\t%s\nSelected\t%s\n",role,title,value,selected);
        return (!strcmp(role,"AXTextArea") || !strcmp(role,"AXTextField")) && !strcmp(selected,"AccessibilityFixture") ? 1 : -1;
    }
    CFTypeRef children = NULL;
    if (AXUIElementCopyAttributeValue(element,kAXChildrenAttribute,&children)==kAXErrorSuccess && children) {
        if(CFGetTypeID(children)==CFArrayGetTypeID()) {
            CFArrayRef list=(CFArrayRef)children;
            for(CFIndex i=0;i<CFArrayGetCount(list);i++) {
                int result=inspect((AXUIElementRef)CFArrayGetValueAtIndex(list,i),depth+1);
                if(result){CFRelease(children);return result;}
            }
        }
        CFRelease(children);
    }
    return 0;
}
int main(int argc,char **argv) {
    if(argc!=2 || atoi(argv[1])<=0)return 2;
    if(!AXIsProcessTrusted()){fprintf(stderr,"AX permission unavailable for this probe\n");return 3;}
    AXUIElementRef app=AXUIElementCreateApplication((pid_t)atoi(argv[1]));
    int result=inspect(app,0);CFRelease(app);
    printf("Visited\t%u\n",visited);
    return result==1?0:4;
}
