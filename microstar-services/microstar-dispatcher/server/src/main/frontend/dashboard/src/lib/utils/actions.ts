// Based on: https://stackoverflow.com/questions/56844807/svelte-long-press

let lastCustomClickTriggerTime = 0;
export function customclick(node, threshold = 500) {
    const handle_mousedown = () => {
        const trigger = long => {
            const now = new Date().getTime();
            if(now - lastCustomClickTriggerTime > 25) {
                lastCustomClickTriggerTime = now;
                node.dispatchEvent(new CustomEvent('customclick', {detail: {long}}));
            }
        };
        const timeout = setTimeout(() => {
            trigger(true);
            cancel(null);
        }, threshold);

        const cancel = evt => {
            clearTimeout(timeout);
            node.removeEventListener('mousemove', cancel);
            node.removeEventListener('mouseup', cancel);
            if(evt?.type === 'mouseup') trigger(false);
        };

        setTimeout(() => node.addEventListener('mousemove', cancel), 100);
        node.addEventListener('mouseup', cancel);
    }

    node.addEventListener('mousedown', handle_mousedown);

    return {
        destroy() {
            node.removeEventListener('mousedown', handle_mousedown);
        }
    };
}

/** Don't use in combination with on:click but use on:shortclick instead */
export function longclick(node, threshold = 500) {
    const handleCustomClick = evt => { if(evt.detail?.long) node.dispatchEvent(new CustomEvent('longclick')); }

    node.addEventListener('customclick', handleCustomClick);
    const cc = customclick(node, threshold);

    return {
        destroy() {
            node.removeEventListener('customclick', handleCustomClick);
            cc.destroy();
        }
    };
}
/** Like on:click but works together with on:longclick */
export function shortclick(node) {
    const handleCustomClick = evt => { if(!evt.detail?.long) node.dispatchEvent(new CustomEvent('shortclick')); }

    node.addEventListener('customclick', handleCustomClick);
    const cc = customclick(node);

    return {
        destroy() {
            node.removeEventListener('customclick', handleCustomClick);
            cc.destroy();
        }
    };
}
