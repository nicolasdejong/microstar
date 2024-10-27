import {fetchData} from "../../../utils/Network";
import type {Service} from "../../../types/Services";

export function startService             (service: Service): Promise<any> { return sendDispatcherCmd('start/' + service.id.combined); }
export function startServiceAndStopOthers(service: Service): Promise<any> { return sendDispatcherCmd('start/' + service.id.combined + "?replaceAll=true"); }
export function restartService           (service: Service): Promise<any> { return sendDispatcherCmd('start/' + service.id.combined + "?replaceInstanceId=" + service.instanceId); }
export function stopService              (service: Service): Promise<any> { return sendCmd(service, 'stop'); }
export function forgetService            (service: Service): Promise<any> { return sendDispatcherCmd('stop/' + service.instanceId); }
export function deleteService            (service: Service): Promise<any> { return sendDispatcherCmd('delete/' + service.id.combined, 'delete'); }
export function gcService                (service: Service): Promise<any> { return sendCmd(service, 'gc'); }

function sendDispatcherCmd(cmd: string, method = 'post'): Promise<any> {
    return fetchData(['', cmd].join('/'), method);
}
function sendCmd(service: Service, cmd: string, method = 'post'): Promise<any> {
    return fetchData(['', service.instanceId, cmd].join('/'), method);
}
