/* Read only the supplied browser's focused window. Never print unrelated tab content. */
#include <ApplicationServices/ApplicationServices.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static unsigned visited;
static char expected[PATH_MAX];
static int matches(CFTypeRef value) {
    if (!value) return 0;
    CFURLRef url = NULL;
    if (CFGetTypeID(value) == CFURLGetTypeID()) url = CFRetain(value);
    else if (CFGetTypeID(value) == CFStringGetTypeID()) {
        url = CFURLCreateWithString(NULL, value, NULL);
    }
    if (!url) return 0;
    UInt8 path[PATH_MAX]; char canonical[PATH_MAX];
    int ok = CFURLGetFileSystemRepresentation(url, true, path, sizeof path)
        && realpath((char *)path, canonical) && !strcmp(expected, canonical);
    CFRelease(url); return ok;
}
static int inspect(AXUIElementRef element, unsigned depth) {
    if (++visited > 6000 || depth > 45) return 0;
    const CFStringRef keys[] = {kAXURLAttribute, kAXValueAttribute, kAXDocumentAttribute};
    for (unsigned i=0;i<3;i++) {
        CFTypeRef value=NULL;
        if (AXUIElementCopyAttributeValue(element,keys[i],&value)==kAXErrorSuccess && value) {
            int ok=matches(value); CFRelease(value); if(ok)return 1;
        }
    }
    CFTypeRef children=NULL;
    if(AXUIElementCopyAttributeValue(element,kAXChildrenAttribute,&children)==kAXErrorSuccess && children) {
        if(CFGetTypeID(children)==CFArrayGetTypeID()) {
            for(CFIndex i=0;i<CFArrayGetCount(children);i++)
                if(inspect((AXUIElementRef)CFArrayGetValueAtIndex(children,i),depth+1)){CFRelease(children);return 1;}
        }
        CFRelease(children);
    }
    return 0;
}
int main(int argc,char **argv) {
    if(argc!=3 || atoi(argv[1])<=0 || !realpath(argv[2],expected))return 2;
    if(!AXIsProcessTrusted()){fprintf(stderr,"AX permission unavailable\n");return 3;}
    AXUIElementRef app=AXUIElementCreateApplication((pid_t)atoi(argv[1]));
    CFTypeRef window=NULL;
    AXError error=AXUIElementCopyAttributeValue(app,kAXFocusedWindowAttribute,&window);
    int ok=error==kAXErrorSuccess && window && inspect(window,0);
    if(window)CFRelease(window);CFRelease(app);
    printf("Focused browser window matches expected report: %s; visited=%u\n",ok?"yes":"no",visited);
    return ok?0:4;
}
