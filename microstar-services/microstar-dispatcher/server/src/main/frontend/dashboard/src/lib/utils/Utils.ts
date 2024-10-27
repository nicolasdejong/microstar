
export function defer(callback: () => void, time? : number) : void {
    setTimeout(callback, time || 1);
}
export function delay(timeMs = 100) : Promise<void> {
    return new Promise(r => setTimeout(r, timeMs));
}

const functionTimeouts = new Map();
export function debounce(functionToDebounce: () => any, debounceTimeMs = 250, ...args : any[]) : void {
    resetDebounce(functionToDebounce);
    functionTimeouts.set(toId(functionToDebounce), setTimeout(() => {
        functionToDebounce.call(null, args)
    }, debounceTimeMs));
}
export function debounceAsFunction<T>(functionToDebounce: () => T, debounceTimeMs = 250) : (...args : any[]) => void {
    return (...args : any[]) => debounce(functionToDebounce, debounceTimeMs, args);
}
export function debounceAsPromise<T>(functionToDebounce: () => Promise<T>, debounceTimeMs = 250, ...args : any[]) : Promise<T> {
    resetDebounce(functionToDebounce);
    return new Promise(r => {
        functionTimeouts.set(toId(functionToDebounce), setTimeout(
            () => {
                resetDebounce(functionToDebounce);
                let result = functionToDebounce.call(null, args);
                return result && typeof result.then === 'function'
                    ? result.then(val => r.call(null, val))
                    : r.call(null, result);
            },
            debounceTimeMs
        ));
    });
}
export function resetDebounce(functionToDebounce: () => any) {
    const timerId = functionTimeouts.get(toId(functionToDebounce));
    if(timerId) {
        functionTimeouts.delete(toId(functionToDebounce));
        clearTimeout(timerId);
    }
}

const functionCallTimes = new Map();
const functionPromises = new Map();
export function throttle(functionToThrottle: () => any, throttleTimeMs = 250, ...args : any[]) : void {
    const lastCallTime = functionCallTimes.get(toId(functionToThrottle)) || 0;
    const now = Date.now();
    if(lastCallTime > now - throttleTimeMs) return; // throttled
    functionCallTimes.set(toId(functionToThrottle), now);
    functionToThrottle.call(null, args);
}
export function throttleAsFunction<T>(functionToThrottle: () => T, throttleTimeMs = 250) : (...args : any[]) => void {
    return (...args : any[]) => throttle(functionToThrottle, throttleTimeMs, args);
}
export function throttleAsPromise<T>(functionToThrottle: () => Promise<T>, throttleTimeMs = 250, ...args : any[]) : Promise<T> {
    const lastCallTime = functionCallTimes.get(toId(functionToThrottle())) || 0;
    const now = Date.now();
    if(lastCallTime > now - throttleTimeMs) return functionPromises.get(toId(functionToThrottle)); // throttled
    const promise = <Promise<T>>new Promise(r => {
        let result = functionToThrottle.call(null, args);
        return result && typeof result.then === 'function'
            ? result.then(val => r.call(null, val))
            : r.call(null, result);
    });
    functionCallTimes.set(toId(functionToThrottle), now);
    functionPromises.set(toId(functionToThrottle), promise);
    return promise;
}
export function resetThrottle(functionToThrottle: () => any) {
    functionCallTimes.delete(toId(functionToThrottle));
    functionPromises.delete(toId(functionToThrottle));
}

function toId(obj : any) : any {
    return obj && typeof obj === 'function'
      ? (obj.name ? obj : obj.toString()) // anonymous functions get recreated each time so don't have a stable reference so run toString() on them
      : obj;
}

export function timeToAge(someTimeAgo: number | Date, skipMinors? : string): string { // NOSONAR -- in this case high cyclox isn't too bad
    const someTimeAgoMs = someTimeAgo instanceof Date ? someTimeAgo.getTime() : someTimeAgo;
    if(someTimeAgoMs < 1000) return '';

    const now = new Date().getTime();
    const millis = now - someTimeAgoMs;
    const seconds = Math.floor(millis  / 1000);
    const minutes = Math.floor(seconds / 60);
    const hours   = Math.floor(minutes / 60);
    const days    = Math.floor(hours   / 24);
    const weeks   = Math.floor(days    / 7);

    if(millis < 0) return 'future';

    let initial;
    // When no default 'skip' units are provided, create a default skip for current ago time
    const skip = skipMinors !== undefined ? skipMinors :
        (millis > 1000 ? 'i' : '') +
        (seconds > 120 ? 's' : '') +
        (hours   >  24 ? 'm' : '') +
        (days    >  10 ? 'h' : '')
    ;

    if(millis  <     1000) initial = millis  + "ms"; else // NOSONAR
    if(seconds <       60) initial = seconds + "s"; else  // NOSONAR
    if(minutes <       60) initial = minutes + "m"; else  // NOSONAR
    if(hours   <       24) initial = hours   + "h"; else  // NOSONAR
    if(days    <        7) initial = days    + "d"; else initial = weeks   + "w";
    return initial
        + (!skip.includes('d') && days    >=    7 && days    %    7 != 0 ? days    %    7 + 'd' : '')
        + (!skip.includes('h') && hours   >=   24 && hours   %   24 != 0 ? hours   %   24 + 'h' : '')
        + (!skip.includes('m') && minutes >=   60 && minutes %   60 != 0 ? minutes %   60 + 'm' : '')
        + (!skip.includes('s') && seconds >=   60 && seconds %   60 != 0 ? seconds %   60 + 's' : '')
        + (!skip.includes('i') && millis  >= 1000 && millis  % 1000 != 0 ? millis  % 1000 + 'ms' : '');
}
export function timeToText(timeUtc : number | Date, showFullTime = false) {
    const now = new Date();
    const dt = new Date(timeUtc);
    dt .setHours(dt.getHours() + (dt.getHours() - dt.getUTCHours()));
    if(isNaN(dt.getTime())) return '?' + timeUtc; // invalid date (or toISOString() will throw)
    const dateAndTime = dt.toISOString().replace(/\.\d+Z$/, '').split(/[TZ]/);
    return showFullTime ? dateAndTime.join(' ') : dateAndTime[now.toDateString() === dt.toDateString() ? 1 : 0];
}

export function isNumber(n : any) { return !isNaN(parseFloat(n)) && !isNaN(n - 0); }
export function asBoolean(value : any) {
    if(value === true || value === false) return value;
    if(isNumber(value)) return Number(value) !== 0;
    return value === 'true' || value === 'TRUE' || value === 't' || value === 'T';
}

export function hash(str, seed = 0) { // cyrb53
    // https://stackoverflow.com/questions/7616461/generate-a-hash-from-string-in-javascript
    let h1 = 0xdeadbeef ^ seed, h2 = 0x41c6ce57 ^ seed;
    for (let i = 0, ch; i < str.length; i++) {
        ch = str.charCodeAt(i);
        h1 = Math.imul(h1 ^ ch, 2654435761);
        h2 = Math.imul(h2 ^ ch, 1597334677);
    }
    h1 = Math.imul(h1 ^ (h1>>>16), 2246822507) ^ Math.imul(h2 ^ (h2>>>13), 3266489909);
    h2 = Math.imul(h2 ^ (h2>>>16), 2246822507) ^ Math.imul(h1 ^ (h1>>>13), 3266489909);
    return 4294967296 * (2097151 & h2) + (h1>>>0);
}

export function concatPath(...paths : string[]) : string {
    const normalizedPaths = paths.filter(path => !!path).map(s => '' + s);
    const prefix = normalizedPaths.join().startsWith('/') ? '/' : '';
    const suffix = normalizedPaths.join().endsWith  ('/') ? '/' : '';
    return prefix
         + normalizedPaths
            .map(path => path.replace(/^\/+|\/+$/g, ''))
            .join('/')
         + suffix;
}

// Base64 code from: https://stackoverflow.com/a/40392850/16553874

export function toBase64(str) {
    if(!str) return str;
    // noinspection JSDeprecatedSymbols -- btoa can be used because the text is pre-processed to ascii
    return btoa(encodeURIComponent(str).replace(/%([0-9A-F]{2})/g, function(match, p1) { // NOSONAR see prev line
        return String.fromCharCode(Number('0x' + p1));
    }));
}
export function fromBase64(str) {
    if(!str) return str;
    try {
        // noinspection JSDeprecatedSymbols -- btoa can be used because the text is ascii, source should be from toBase64() above
        return decodeURIComponent(Array.prototype.map.call(atob(str), function (c) { // NOSONAR see prev line
            return '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2);
        }).join(''));
    } catch(error) {
        // happens when input is not base64
        return '';
    }
}

export function sizeToString(sizeIn : number) : string {
  const ONE_K = 1024;
  if(sizeIn === undefined || isNaN(sizeIn) || sizeIn < 0) return '';
  const SUFFIXES = [ "", "K", "M", "G", "T", "P" ];
  let exp = Math.min(SUFFIXES.length-1, Math.floor((Math.log(sizeIn) / Math.log(ONE_K))));
  let size = sizeIn / Math.pow(ONE_K, exp);
  if(size <= 4 && exp > 0) { exp--; size = sizeIn / Math.pow(ONE_K, exp); }
  return Math.floor(size) + " " + SUFFIXES[exp] + "B";
}

export function dedupArray<T>(array: Array<T>, mapper: (from: T) => any = a=>a): void {
    const knownValues = new Set<any>();
    for(let i=0; i<array.length; i++) {
        const mappedValue = mapper(array[i]);
        if(knownValues.has(mappedValue)) {
            array.splice(i--, 1);
        } else {
            knownValues.add(mappedValue);
        }
    }
}
