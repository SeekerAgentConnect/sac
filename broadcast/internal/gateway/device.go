package gateway

import (
	"context"
	"crypto/sha256"
	"errors"
	"time"

	"connectrpc.com/connect"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/gateway/v1"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/rules"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/store"
)

type deviceKey struct{}

func deviceOf(ctx context.Context) *store.Binding {
	binding, _ := ctx.Value(deviceKey{}).(*store.Binding)
	return binding
}

func AuthenticatingDevice(devices *store.Store) connect.UnaryInterceptorFunc {
	return func(next connect.UnaryFunc) connect.UnaryFunc {
		return func(ctx context.Context, request connect.AnyRequest) (connect.AnyResponse, error) {
			match := bearerPattern.FindStringSubmatch(request.Header().Get("Authorization"))
			if match == nil {
				return nil, unauthenticated()
			}
			sum := sha256.Sum256([]byte(match[1]))
			binding, err := devices.DeviceFor(ctx, sum[:])
			if err != nil {
				return nil, connect.NewError(connect.CodeInternal, err)
			}
			if binding == nil {
				return nil, unauthenticated()
			}
			return next(context.WithValue(ctx, deviceKey{}, binding), request)
		}
	}
}

type Device struct {
	store *store.Store
	now   func() time.Time
}

func NewDevice(from *store.Store, now func() time.Time) *Device {
	return &Device{store: from, now: now}
}

func (d *Device) GetServerManifest(
	ctx context.Context,
	request *connect.Request[gatewayv1.DeviceServiceGetServerManifestRequest],
) (*connect.Response[gatewayv1.DeviceServiceGetServerManifestResponse], error) {
	binding, failure := requireDevice(ctx, request.Msg.GetConnectionId())
	if failure != nil {
		return nil, failure
	}
	held, err := d.store.Manifest(ctx, binding.ServerID)
	if err != nil {
		return nil, internal(err)
	}
	if held == nil {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_SERVER, "connection_id")
	}
	revision := held.Document.GetSettingsRevision()
	answer := &gatewayv1.DeviceServiceGetServerManifestResponse{SettingsRevision: revision}
	if request.Msg.GetKnownSettingsRevision() == revision {
		answer.Unchanged = true
	} else {
		answer.Manifest = held.Document
	}
	return connect.NewResponse(answer), nil
}

func (d *Device) ListRequests(
	ctx context.Context,
	request *connect.Request[gatewayv1.DeviceServiceListRequestsRequest],
) (*connect.Response[gatewayv1.DeviceServiceListRequestsResponse], error) {
	binding, failure := requireDevice(ctx, request.Msg.GetConnectionId())
	if failure != nil {
		return nil, failure
	}
	size := int(request.Msg.GetPageSize())
	if size == 0 {
		size = 50
	}
	if size < 1 || size > 100 {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_PAGE_SIZE, "page_size")
	}
	scope := "device/" + binding.ConnectionID
	after := ""
	snapshot := binding.Sequence
	if request.Msg.GetPageToken() != "" {
		var fault *rules.Fault
		snapshot, after, fault = decodeCursor(request.Msg.GetPageToken(), scope)
		if fault != nil {
			return nil, refuse(fault)
		}
	} else if request.Msg.GetKnownSequence() == binding.Sequence {
		return connect.NewResponse(&gatewayv1.DeviceServiceListRequestsResponse{
			Sequence: binding.Sequence, Unchanged: true}), nil
	}
	page, _, err := d.store.PrivatePage(ctx, binding.ConnectionID, after, size+1)
	if err != nil {
		return nil, internal(err)
	}
	answer := &gatewayv1.DeviceServiceListRequestsResponse{Sequence: snapshot}
	if len(page) > size {
		answer.NextPageToken = encodeCursor(scope, snapshot,
			page[size-1].Document.GetIdentity().GetRequestId())
		page = page[:size]
	}
	for _, one := range page {
		answer.Requests = append(answer.Requests, one.Document)
	}
	return connect.NewResponse(answer), nil
}

func (d *Device) SubmitResult(
	ctx context.Context,
	request *connect.Request[gatewayv1.DeviceServiceSubmitResultRequest],
) (*connect.Response[gatewayv1.DeviceServiceSubmitResultResponse], error) {
	binding, failure := requireDevice(ctx, request.Msg.GetConnectionId())
	if failure != nil {
		return nil, failure
	}
	message := request.Msg.GetResult()
	if message == nil || !rules.IsID(message.GetRequestId()) {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_REQUEST, "result.request_id")
	}
	held, err := d.store.PrivateRequest(ctx, binding.ServerID, message.GetRequestId())
	if err != nil {
		return nil, internal(err)
	}
	if held == nil || held.ConnectionID != binding.ConnectionID {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_REQUEST, "result.request_id")
	}
	validated, fault := rules.DeviceResult(message, held.Document)
	if fault != nil {
		return nil, refuse(fault)
	}
	unchanged, err := d.store.PutDeviceResult(ctx, binding.ServerID, binding.ConnectionID, validated, d.now())
	switch {
	case errors.Is(err, store.ErrNoPrivateRequest):
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_REQUEST, "result.request_id")
	case errors.Is(err, store.ErrResultConflict):
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_RESULT_CONFLICT, "result")
	case err != nil:
		return nil, internal(err)
	}
	return connect.NewResponse(&gatewayv1.DeviceServiceSubmitResultResponse{
		Result: validated, Unchanged: unchanged}), nil
}

func (d *Device) RevokeConnection(
	ctx context.Context,
	request *connect.Request[gatewayv1.DeviceServiceRevokeConnectionRequest],
) (*connect.Response[gatewayv1.DeviceServiceRevokeConnectionResponse], error) {
	binding, failure := requireDevice(ctx, request.Msg.GetConnectionId())
	if failure != nil {
		return nil, failure
	}
	if err := d.store.RevokeBinding(ctx, binding.ConnectionID, d.now()); err != nil {
		return nil, internal(err)
	}
	return connect.NewResponse(&gatewayv1.DeviceServiceRevokeConnectionResponse{}), nil
}

func requireDevice(ctx context.Context, connectionID string) (*store.Binding, *connect.Error) {
	binding := deviceOf(ctx)
	if binding == nil {
		return nil, unauthenticated()
	}
	if connectionID != binding.ConnectionID {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_SERVER, "connection_id")
	}
	return binding, nil
}
