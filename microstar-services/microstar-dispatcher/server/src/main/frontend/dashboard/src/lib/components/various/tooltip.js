import Tooltip from './TooltipFromAction.svelte';

// Based on REPL: https://svelte.dev/repl/dd6754a2ad0547c5b1c1ea37c0293fef?version=3.49.0

// Share tooltipComponent between elements so the old one is always removed when showing a new one
let tooltipComponent;

export function tooltip(element) {
    const delay = 1000; // ms
    let timer;
    let lastEvent;
    function mouseOver(event, showImmediately) {
        lastEvent = event;
        cleanup();
        if(!showImmediately) {
            timer = setTimeout(() => mouseOver(lastEvent, true), delay);
            return;
        }

        // When element is removed from the DOM while the tooltip is showing, the tooltip should go away
        timer = setInterval(() => {
            if(!isPartOfBody(element)) cleanup();
        }, 100);

        // remove the `title` attribute, to prevent showing the default browser tooltip
        // remember to set it back on `mouseleave`
        element.ttTitle = element.getAttribute('title');
        element.removeAttribute('title');

        tooltipComponent = new Tooltip({
            props: {
                title: element.ttTitle,
                x: event.pageX,
                y: event.pageY,
            },
            target: document.body,
        });
    }
    function mouseMove(event) {
        lastEvent = event;
        if(tooltipComponent) tooltipComponent.$set({
            x: event.pageX,
            y: event.pageY,
        })
    }
    function mouseLeave() {
        cleanup();
        // restore the `title` attribute
        if(element.ttTitle) element.setAttribute('title', element.ttTitle);
    }
    function cleanup() {
        if(timer) clearTimeout(timer);
        timer = null;
        if(tooltipComponent) tooltipComponent.$destroy();
        tooltipComponent = null;
    }

    element.addEventListener('mouseover', mouseOver);
    element.addEventListener('mouseleave', mouseLeave);
    element.addEventListener('mousemove', mouseMove);

    // noinspection JSUnusedGlobalSymbols
    return {
        destroy() {
            element.removeEventListener('mouseover', mouseOver);
            element.removeEventListener('mouseleave', mouseLeave);
            element.removeEventListener('mousemove', mouseMove);
        }
    }
}

function isPartOfBody(element) {
    for(let elem = element; elem; elem = elem.parentNode) {
        if(elem.nodeName === 'BODY') return true;
    }
    return false;
}