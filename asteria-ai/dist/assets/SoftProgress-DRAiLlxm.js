import{c as n,_ as l}from"./index-CyqzNTNY.js";import{G as d,M as o,P as r,R as p,$ as i,Z as u,k as h,L as c}from"./vendor-BtDxEGf_.js";/**
 * @license lucide-vue-next v1.0.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const y=n("rotate-ccw",[["path",{d:"M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8",key:"1357e3"}],["path",{d:"M3 3v5h5",key:"1xhq8a"}]]),_=["title"],f={class:"soft-progress-track"},m={key:0,class:"soft-progress-text"},v=d({__name:"SoftProgress",props:{value:{default:0},showText:{type:Boolean,default:!1}},setup(s){const e=s,t=h(()=>{const a=e.value>1?e.value/100:e.value;return Math.min(1,Math.max(0,a))});return(a,x)=>(c(),o("div",{class:"soft-progress",title:`${Math.round(t.value*100)}%`},[r("div",f,[r("div",{class:"soft-progress-bar",style:p({width:`${t.value*100}%`})},null,4)]),s.showText?(c(),o("span",m,i(Math.round(t.value*100))+"%",1)):u("",!0)],8,_))}}),M=l(v,[["__scopeId","data-v-ed4e6bee"]]);export{y as R,M as S};
