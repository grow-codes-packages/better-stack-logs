// Package betterstack is the Grow Codes SDK for Better Stack Logs.
//
// Better Stack has two hosts with separate credentials: the source's ingesting host
// (GROW_BETTER_STACK_LOGS_URL + GROW_BETTER_STACK_LOGS_SOURCE_TOKEN, or
// BETTERSTACK_INGESTING_HOST + BETTERSTACK_SOURCE_TOKEN) and the SQL query host
// (GROW_BETTER_STACK_LOGS_QUERY_URL, _QUERY_USERNAME, _QUERY_PASSWORD, or the
// BETTERSTACK_QUERY_* variables). Create a client from the environment or from a
// Config literal:
//
//	client, err := betterstack.NewClientFromEnv()
//	if err != nil {
//		return err
//	}
//	defer client.Close()
//
//	logs, err := client.NewLogClient(betterstack.LogClientOptions{})
//	if err != nil {
//		return err
//	}
//	logs.Logger("checkout", nil).Info(ctx, "order placed", "order_id", 42)
//	_ = logs.Flush(ctx)
//
//	rows, err := client.Query().SearchLogs(ctx, betterstack.SearchLogsOptions{Contains: "order placed"})
//
// Every error is an *Error; compare codes with errors.Is against the Err*
// sentinels (errors.Is(err, betterstack.ErrNotFound)). Credentials are wrapped in
// Secret and never appear in errors, hooks or logs.
//
// Better Stack is a trademark of Better Stack, Inc. This package is not affiliated
// with or endorsed by Better Stack, Inc.
package betterstack
