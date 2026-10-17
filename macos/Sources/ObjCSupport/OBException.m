#import "OBException.h"

BOOL OBCatchException(void (NS_NOESCAPE ^block)(void), NSError **error) {
    @try {
        block();
        return YES;
    } @catch (NSException *exception) {
        if (error) {
            NSString *message = [NSString stringWithFormat:@"%@: %@", exception.name, exception.reason ?: @"no reason given"];
            *error = [NSError errorWithDomain:@"org.openbridge.exception" code:1
                                     userInfo:@{NSLocalizedDescriptionKey: message}];
        }
        return NO;
    }
}
