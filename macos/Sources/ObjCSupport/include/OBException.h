#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Runs `block` and turns an Objective-C exception into an NSError. Swift cannot catch these exceptions,
/// so without this an unexpected one from Apple's USB framework would crash the app.
BOOL OBCatchException(void (NS_NOESCAPE ^block)(void), NSError *_Nullable *_Nullable error);

NS_ASSUME_NONNULL_END
