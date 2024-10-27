<script lang="ts">

import {onMount} from "svelte";

export let leftRight: boolean = false;
export let topBottom: boolean = true;
export let showButtons: boolean = false;
export let storeName: string = 'split';
export let defaultPos: string = '50';
export let floatingTitle: boolean = false;

let pos: number = 50;
let offset: number = 0;
let draggingSplit = false;
let splitPanel: HTMLDivElement;
let leftTopPanel: HTMLDivElement;
let rightBottomPanel: HTMLDivElement;
let linePanel: HTMLDivElement;

onMount(() => {
    if(leftRight) topBottom = false;
    if(topBottom) leftRight = false;
    updateLayout();
});

function disableEventsInPanels(): void { leftTopPanel.style.pointerEvents = 'none'; rightBottomPanel.style.pointerEvents = 'none'; }
function enableEventsInPanels():  void { leftTopPanel.style.pointerEvents = 'auto'; rightBottomPanel.style.pointerEvents = 'auto'; }

function splitDragStart(evt: MouseEvent): void {   draggingSplit = true; disableEventsInPanels(); offset = leftRight ? evt.offsetX : evt.offsetY; updateLayout(evt); }
function splitDrag(evt: MouseEvent): void {     if(draggingSplit)        updateLayout(evt); }
function splitDragStop(evt: MouseEvent): void { if(draggingSplit)        updateLayout(evt); draggingSplit = false; enableEventsInPanels(); }
function updateLayout(evt?: MouseEvent, perc?: number): void {
    const panelRect  = splitPanel.getBoundingClientRect();
    const xy         = (evt ? (leftRight ? evt.clientX - panelRect.x : evt.clientY - panelRect.y) - offset : undefined);
    const dimension1 = leftRight ? 'width' : 'height';
    const dimension2 = leftRight ? 'left' : 'top';

    if(perc !== undefined) pos = perc; else
    if(xy === undefined) {
        pos = parseFloat(localStorage[storeName] || defaultPos);
    } else {
        const parentSize = (<any>splitPanel)[dimension1 == 'width' ? 'offsetWidth' : 'offsetHeight'];
        pos = Math.round(1000 * xy / parentSize) / 10;
    }
    pos = Math.min(90, Math.max(5, pos));
    localStorage[storeName] = pos;

    leftTopPanel    .style[dimension1] = 'calc(' +      pos  + '% )';
    rightBottomPanel.style[dimension2] = 'calc(' +      pos  + '% + 7px)';
    rightBottomPanel.style[dimension1] = 'calc(' + (100-pos) + '% - 7px)';
    linePanel       .style[dimension2] = 'calc(' +      pos  + '%)';
}
</script>

<svelte:window on:mousemove={evt=>splitDrag(evt)} on:mouseup={evt=>splitDragStop(evt)}/>

<div class="SplitPanel"
     class:LeftRight={leftRight}
     class:TopBottom={topBottom}
     class:WithButtons={showButtons}
     class:Dragging={draggingSplit}
     bind:this={splitPanel}
     on:mousedown={evt=>{if(evt.target===linePanel)splitDragStart(evt);}}>

    <div class:left={leftRight}
         class:top={topBottom}
         bind:this={leftTopPanel}>
        <slot name="left"></slot>
        <slot name="top"></slot>
    </div>
    <div class="line" bind:this={linePanel} on:dblclick={_ => updateLayout(null,50)}>
        <div class="title" class:floating={floatingTitle}>
            <slot name="line"></slot>
        </div>
        <div class="split-buttons">
            <div on:click={() => {updateLayout(null, 5);}} on:keydown={()=>{}}>&#x25B2;</div>
            <div on:click={() => {updateLayout(null,50);}} on:keydown={()=>{}}>&bull;</div>
            <div on:click={() => {updateLayout(null,90);}} on:keydown={()=>{}}>&#x25BC;</div>
        </div>
    </div>
    <div class:right={leftRight}
         class:bottom={topBottom}
         bind:this={rightBottomPanel}>
        <slot name="right"></slot>
        <slot name="bottom"></slot>
    </div>
</div>

<style lang="scss">

.SplitPanel {
  position: relative;
  background-color: transparent;
  width: 100%;
  height: 100%;

  >.left, >.right, >.top, >.bottom,
  :global(div[slot="left"]), :global(div[slot="right"]), :global(div[slot="top"]), :global(div[slot="bottom"]) {
    width:100%;
    height:100%;
    box-sizing: border-box;
    overflow: hidden;
  }

  &.Dragging { user-select: none; }

  > .line {
    position:absolute;
    z-index:99;
    user-select: none;

    // from: https://www.magicpattern.design/tools/css-backgrounds
    background-color: var(--background-color);
    opacity: 0.8;
    background-image: radial-gradient( ellipse farthest-corner at 4px 4px , #888, #888 50%, var(--background-color) 50%);
    background-size: 3px 3px;

    > .title {
      :global(> div:empty) { display: none; }
      :global(> div) { border:1px solid var(--border-color); padding: 0 0.25em; }
      &.floating { position:absolute; }
      display: inline-block;
      font-size: 100%;
      background: var(--background-color);

      :global(*:not(div)) { pointer-events: all; cursor: auto; }
      pointer-events: none;
    }
    > .split-buttons {
      position:absolute;
      display:none; font-size:110%;
      background: var(--background-color);
      border:1px solid var(--border-color);
      > div {
        cursor: pointer;
        &:hover { color: var(--text-color-hover); }
      }
    }
  }
  &.WithButtons > .line > .split-buttons { display:flex; }
  .left, .top, .right, .bottom {
    background-color: var(--background-colors);
  }

  &.TopBottom {
    > .bottom {
      position:absolute;
      left:0; width:100%; height:calc(50% - 6px); top:calc(50% + 6px);
    }
    > .line {
      left:0; width:100%; top:50%; min-height:6px;
      cursor: n-resize;

      > .title {
        left:0.5rem; top:-0.6em;
      }
      > .split-buttons {
        right:20px; top:-5px;
        padding-top:5px;
        > div {
          height:1em;
          margin-top:-0.5em;
          margin-bottom:0.1em;
          padding-right: 1px;
        }
      }
    }
  }
  &.LeftRight {
    > .right {
      position:absolute;
      top:0; height:100%; width:calc(50% - 6px); left:calc(50% + 6px);
      //background-color: var(--background-color-faded);
    }
    > .line {
      top:0; height:100%; left:50%; min-width:6px;
      cursor: e-resize;

      > .title {
        top:0.5rem; left:-9px;
        padding: 0.5rem 0;
      }
      > .split-buttons {
        flex-direction: column;
        text-align: center;
        bottom:20px; left:-7px;
        padding-left:5px;
        line-height: 1em;
        > div {
          transform: rotate(-90deg);
          width:1em;
          margin-left: -0.3em;
          margin-right: -0.1em;
          margin-bottom: -0.25em;
          &:last-child { margin-bottom: 0; }
        }
      }
    }
  }
}
</style>
