// Package betterstack is the Grow Codes SDK for Better Stack Logs.
//
// Create a client from the environment (GROW_BETTER_STACK_LOGS_URL,
// GROW_BETTER_STACK_LOGS_TOKEN, ...) or from a Config literal:
//
//	client, err := betterstack.NewClientFromEnv()
//	if err != nil {
//		return err
//	}
//	defer client.Close()
//	status, err := client.Health(ctx)
//
// Every error is an *Error; compare codes with errors.Is against the Err*
// sentinels (errors.Is(err, betterstack.ErrNotFound)). Credentials are wrapped in
// Secret and never appear in errors, hooks or logs.
//
// Better Stack Logs is a trademark of Better Stack, Inc.. This package is not affiliated
// with or endorsed by Better Stack, Inc..
package betterstack
