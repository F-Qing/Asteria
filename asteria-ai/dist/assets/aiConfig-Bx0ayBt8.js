import{c as r,A as s,h as a,w as t,r as d}from"./index-CyqzNTNY.js";import{az as l}from"./vendor-BtDxEGf_.js";/**
 * @license lucide-vue-next v1.0.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const h=r("plus",[["path",{d:"M5 12h14",key:"1ays0h"}],["path",{d:"M12 5v14",key:"s699le"}]]),m=l("aiConfig",{state:()=>({provider:"openai",apiKey:"",baseUrl:s[0].baseUrl,model:"",...d()??{}}),getters:{isConfigured:e=>!!(e.apiKey&&e.model),modelSuggestions:e=>{var i;return((i=s.find(o=>o.key===e.provider))==null?void 0:i.models)??[]}},actions:{save(){t({provider:this.provider,apiKey:this.apiKey,baseUrl:this.baseUrl,model:this.model})},clear(){var e;a(),this.apiKey="",this.baseUrl=((e=s.find(i=>i.key===this.provider))==null?void 0:e.baseUrl)??"",this.model=""},applyProvider(e){this.provider=e;const i=s.find(o=>o.key===e);i&&(this.baseUrl=i.baseUrl,this.model&&i.models.length&&!i.models.includes(this.model)&&(this.model=""))}}});export{h as P,m as u};
