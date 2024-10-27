import {defer} from "./Utils";

export function addRawLog(logText: string, logPanel: HTMLDivElement): void {
    if(logText === undefined || !logPanel) return;

    const isScrolledDown = logPanel.scrollTop >= logPanel.scrollHeight - logPanel.offsetHeight - 30;
    const div = document.createElement('div');
    div.setAttribute('class', 'block');

    div.innerHTML = decorateLog(escapeRawLog(logText));
    logPanel.append(div);

    // stay scrolled down
    if(isScrolledDown) defer(() => logPanel.scrollTop = logPanel.scrollHeight);
}

export function escapeRawLog(text: string): string {
    return text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}

export function decorateLog(text: string): string {
    return text
        // Windows newlines
        .replace(/\r\n/g, '\n')
        // QUOTED
        .replace(/("[^\n"]+")/g, '<span class="Quoted">$1</span>')
        // NUMBER
        .replace(/(\s)([\d.]+)([\s)\]]|$)/g, '$1<span class="Number">$2</span>$3')
        // LEVEL
        .replace(/( )(ERROR|WARN|INFO|DEBUG|TRACE)( )/g, '$1<span class="Level $2">$2</span>$3')
        // CLASS
        .replace(/( )([\w.]{10,})(\s+:)/g, '$1<span class="Class">$2</span>$3')
        // STACK TRACE COLLAPSE
        .replace(/^([^\n]+)\n((?:[\t ]+(?:at|\\.\\.\\.) .*?\n)+)\n?/gm,
            '<div class="expand-button"></div><div class="expand-parent">$1</div><div class="expandable">$2</div>')
        ;
}
