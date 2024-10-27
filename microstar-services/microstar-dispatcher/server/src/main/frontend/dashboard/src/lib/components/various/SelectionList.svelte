<script lang="ts" context="module">
    export type List = {
        items: ListItem[],
        lastSelection?: ListItem,
        multiSelection?: boolean,
        noSelection?: boolean,
        showDeleted?: boolean,
        singleStarred?: boolean,
        onlySetStar?: boolean
    }
    export type ListItem = {
        name: string,
        text?: string,
        html?: string,
        data?: any,
        isSelected?: boolean,
        isDeleted?: boolean,
        isStarred?: boolean,
        canBeRestored?: boolean,
        canBeDeleted?: boolean,
        canBeRenamed?: boolean,
        canBeStarred?: boolean
    }
</script>

<script lang="ts">
    import Trashcan from "./Trashcan.svelte";
    import {tooltip} from "./tooltip";
    import {createEventDispatcher} from 'svelte';

    const dispatch = createEventDispatcher();

    // Events: restore, delete, rename with event.detail.item holding the target item
    // and rename will have an event.detail.newName

    export let list: List = { items: [], showDeleted: true };
    export let starredItem: ListItem = undefined;
    let renaming: ListItem;
    let renamingText: string;
    let beforeRenamingText: string;


    function renameItem(item: ListItem, newName: string): void {
        item.name = item.text = newName;
        triggerRename(item, newName, beforeRenamingText);
    }
    export function rename(): void {
        if(!list.lastSelection || renaming) return;
        renamingText = list.lastSelection.text;
        renaming = list.lastSelection;
    }
    function initInput(input: HTMLInputElement): void {
        input.focus();
    }

    export function setSelection(item: ListItem): void {
        if(list.noSelection) return;
        if(!list.multiSelection) list.items.forEach(item => item.isSelected = false);
        item.isSelected = true;
        list.lastSelection = item;
        if(item) dispatch('lastSelection', {item});
    }
    function toggleStarred(item: ListItem): void {
        if(item.isStarred && list.onlySetStar) return;
        const newValue = !item.isStarred;
        if(list.singleStarred) {
            list.items.forEach(listItem => { listItem.isStarred = (listItem == item) ? newValue : false; })
        }
        item.isStarred = newValue;
        starredItem = item.isStarred ? item : null;
        dispatch('starred', {item});
    }

    function triggerDelete(item: ListItem): void { dispatch('delete', {item}); }
    function triggerRestore(item: ListItem): void { dispatch('restore', {item}); }
    function triggerRename(item: ListItem, newName: string, oldName: string): void { dispatch('rename', {list, item, newName, oldName}); }
</script>

<div class="container" class:renaming={!!renaming}>
    {#each (list?.items || []) as item}
        {#if list.showDeleted || !item.isDeleted}
          <div class="item" class:selected={item.isSelected}
                            class:isDeleted={item.isDeleted}
                            class:canBeRestored={item.canBeRestored}
                            class:canBeDeleted={item.canBeDeleted}
                            class:canBeRenamed={item.canBeRenamed}
                            class:canBeStarred={item.canBeStarred && (!list.onlySetStar || !starredItem)}
                            class:isStarred={item.isStarred}
                            class:renaming={!!renaming}
                            on:click={() => setSelection(item)}
                            on:keydown={() => {}}>
            <span class="content">
              {#if item.html                  && !(renaming === item)}<span class="html">{@html item.html}</span>
              {:else}
                {#if (item.text || item.name) && !(renaming === item)}<span class="text">{item.text || item.name}</span>{/if}
              {/if}
              {#if                               !(renaming === item)}<span class="starred">&starf;{#if item.isStarred}{#each {length: item.isStarred?0:1} as _}&starf;{/each}{/if}</span>{/if}
              {#if renaming === item}
                <input type="text"
                       bind:value={renamingText}
                       on:keydown={evt => {
                           if(evt.key === 'Enter') { renameItem(item, renamingText); renaming = null; }
                           if(evt.key === 'Escape') renaming = null;
                       }}
                       use:initInput
                />
              {/if}
            </span>
            <span class="separator"></span>
            <span class="buttons">
                <span class="ok"      title="Ok"      use:tooltip on:keydown={() => {}} on:click|stopPropagation={() => { renameItem(item, renamingText); renaming = null; }}>&check;</span>
                <span class="cancel"  title="Cancel"  use:tooltip on:keydown={() => {}} on:click|stopPropagation={() => renaming = null}>&#10005;</span>
                <span class="star"    title="Star"    use:tooltip on:keydown={() => {}} on:click|stopPropagation={() => { toggleStarred(item); item.isStarred = item.isStarred; }}>&starf;</span>
                <span class="rename"  title="Rename"  use:tooltip on:keydown={() => {}} on:click|stopPropagation={() => { beforeRenamingText = renamingText = item.text || item.name; renaming = item; }}>&#9998;</span>
                <span class="recycle" title="Restore" use:tooltip on:keydown={() => {}} on:click|stopPropagation={() => triggerRestore(item)}>&#8513;</span>
                <span class="trashcan"><Trashcan revert tooltip="Remove" on:click={() => triggerDelete(item)}></Trashcan></span>
            </span>
          </div>
        {/if}
    {/each}
</div>

<style lang="scss">
  @use '../../../mixins.scss';

.container {
  width: 100%;
  position: relative;
  user-select: none;
  padding-bottom: 5px; // prevent scrollbar from showing when hovering over icon on last line

  .item {
    padding: 0 0.1em 0 0.4em;
    margin: 0.1em 0.2em;
    cursor: pointer;
    position: relative;
    white-space: nowrap;
    display: flex;
    align-items: flex-end;

    .separator { flex-grow: 1; }
    .starred { visibility: hidden; }

    &.isStarred {
      .starred { visibility: visible; }
    }

    .buttons {
      visibility: hidden;
      padding: 0 0.5em 0 0.5em;
      z-index: 2;
      right: 0;
      // either position absolute, or give a width
      position: absolute; top:0; height:100%; background-color: var(--text-color-hover);
      display: inline-block;
      text-align: right;
      .trashcan, .recycle, .rename, .star, .isDeleted, .ok, .cancel {
        display: none;
        transition: scale;
        transition-duration: 0.25s;
        white-space: nowrap;

        &:hover { transform: scale(1.3); }
      }
      .trashcan { padding: 0 0.25em; }
    }
    &:hover .buttons { visibility: visible; }

    &.selected { @include mixins.item-selection; }
    &:hover { @include mixins.item-hover; }
  }
  &.renaming .content { flex-grow: 1; }
  &.renaming .separator { flex-grow: 0; }
  &.renaming .buttons { width: 2em; }

  &.renaming .item.selected {
    padding-right: 0.5em;
    .buttons { visibility: visible; width: auto; padding: 0 0 0 0.25em; }
    input { font-size: 110%; border-width: 0; margin: 0; position: relative; top: 1px; left: -2px; width: 100%; }
    .ok, .cancel { display: initial; }
    .ok { color:green; font-weight: bold; }
    .cancel { color:red; padding: 0 0 0 0.25em; }
  }
  :not(.renaming) .rename { padding: 0; margin: 0; font-size: 110%; transition: all; transition-duration: 0.25s; }

  &:not(.renaming) {
    .item.selected.canBeRenamed:not(.isDeleted) .rename,
    .item.selected.canBeDeleted:not(.isDeleted) .trashcan,
    .item.selected.canBeRestored .recycle,
    .item.selected.canBeStarred .star { display: initial; }
  }

  .item.isDeleted:not(.selected) { color: var(--text-color-faded); }
  .item.isDeleted span.recycle { font-size:120%; float:right; }
}
</style>